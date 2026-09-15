package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.core.internal.bencode.*;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.peer.transport.NioTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import io.github.oatelauser.thunder.core.internal.tracker.PeerIds;
import io.github.oatelauser.thunder.core.internal.wire.*;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.*;

import static java.nio.file.StandardOpenOption.READ;

/**
 * 支持 BEP 10/9 的测试种子方：应答扩展握手（声明 ut_metadata + metadata_size）与
 * ut_metadata 分块请求（msg_type=1 + 16KiB data），同时照常供数据下载。
 * 需要提供 .torrent 的完整原始字节（info 字典从中切出）。
 */
public final class MetadataSeeder implements AutoCloseable {

    private static final int METADATA_BLOCK = 16 * 1024;
    private static final int UT_METADATA_ID = 1; // 我们侧的子 ID，客户端按 m 字典对端值回发

    private final NioTransport transport;
    private final TorrentMetadata meta;
    private final byte[] infoDict;
    private final java.nio.channels.FileChannel content;

    private MetadataSeeder(NioTransport transport, TorrentMetadata meta, byte[] infoDict, FileChannel content) {
        this.transport = transport;
        this.meta = meta;
        this.infoDict = infoDict;
        this.content = content;
    }

    /**
     * torrentBytes 为 .torrent 原始文件（info 区间按 Bencode 位置切出）；contentFile 为完整数据。
     */
    public static MetadataSeeder start(Path contentFile, TorrentMetadata meta, byte[] torrentBytes) throws IOException {
        byte[] info = extractInfoDict(torrentBytes);
        NioTransport transport = new NioTransport(PeerIds.generate());
        MetadataSeeder seeder = new MetadataSeeder(transport, meta, info, FileChannel.open(contentFile, READ));
        transport.listen(0, infoHash -> Arrays.equals(infoHash, meta.infoHash()) ? seeder.handler() : null);
        return seeder;
    }

    private static byte[] extractInfoDict(byte[] torrentBytes) {
        String marker = "4:infod";
        outer:
        for (int i = 0; i < torrentBytes.length - marker.length(); i++) {
            for (int j = 0; j < marker.length(); j++) {
                if (torrentBytes[i + j] != marker.getBytes()[j]) {
                    continue outer;
                }
            }
            int start = i + 6; // "4:info" 之后，指向 'd'
            // 用解码器消费整个 info 值：结束 position 即字典字节边界。
            // 手写配对扫描不可靠——键内容里的 'e'/'d'/'l'（如 "name"）会被误认作结构字符。
            // 注意 wrap(array, start, …) 的 position 是数组绝对偏移，结束点就是 position 本身。
            ByteBuffer buf = ByteBuffer.wrap(torrentBytes, start, torrentBytes.length - start);
            Bencode.decodeValue(buf);
            return Arrays.copyOfRange(torrentBytes, start, buf.position());
        }
        throw new IllegalArgumentException("cannot locate info dict in torrent bytes");
    }

    public int port() {
        return transport.listeningPort();
    }

    public void announceTo(EmbeddedTracker tracker) {
        tracker.register(meta.infoHash(), port());
    }

    private TransportHandler handler() {
        return new TransportHandler() {
            @Override
            public void onConnected(PeerChannel channel) {
                channel.setMessageListener(messages -> {
                    List<PeerWireMessage> responses = new ArrayList<>(messages.size());
                    for (PeerWireMessage message : messages) {
                        if (message instanceof ExtendedMessage ext) {
                            responses.addAll(handleExtended(ext));
                        } else if (message instanceof Request request) {
                            try {
                                responses.add(new PieceMessage(request.pieceIndex(), request.begin(), readBlock(request)));
                            } catch (IOException e) {
                                return;
                            }
                        }
                    }
                    channel.write(responses);
                });
                Bitfield all = new Bitfield(meta.pieceCount());
                for (int i = 0; i < meta.pieceCount(); i++) {
                    all.set(i);
                }
                channel.write(new BitfieldMessage(all.toBytes()));
                channel.write(Unchoke.INSTANCE);
            }

            @Override
            public void onConnectFailed(InetSocketAddress address, @Nullable Throwable cause) {
            }
        };
    }

    private java.util.List<PeerWireMessage> handleExtended(ExtendedMessage ext) {
        java.util.List<PeerWireMessage> out = new java.util.ArrayList<>();
        if (ext.extendedId() == 0) {
            // 扩展握手：m{ut_metadata:1} + metadata_size
            Map<BString, BencodeValue> handshake = new TreeMap<>(BString.UNSIGNED_ORDER);
            handshake.put(BString.of("m"), new BDict(Map.of(BString.of("ut_metadata"), new BInteger(UT_METADATA_ID))));
            handshake.put(BString.of("metadata_size"), new BInteger(infoDict.length));
            out.add(new ExtendedMessage(0, Bencode.encode(new BDict(handshake))));
            return out;
        }
        if (ext.extendedId() == UT_METADATA_ID) {
            BencodeValue decoded =
                    Bencode.decode(ext.payload());
            if (!(decoded instanceof BDict dict)) {
                return out;
            }
            int piece = dict.get("piece") instanceof BInteger p ? (int) p.value() : -1;
            if (piece < 0) {
                return out;
            }
            int from = piece * METADATA_BLOCK;
            int to = (int) Math.min((long) from + METADATA_BLOCK, infoDict.length);
            byte[] data = Arrays.copyOfRange(infoDict, from, to);
            java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
            Map<BString, BencodeValue> header = new TreeMap<>(BString.UNSIGNED_ORDER);
            header.put(BString.of("msg_type"), new BInteger(1));
            header.put(BString.of("piece"), new BInteger(piece));
            header.put(BString.of("total_size"), new BInteger(infoDict.length));
            payload.writeBytes(Bencode.encode(new BDict(header)));
            payload.writeBytes(data);
            out.add(new ExtendedMessage(UT_METADATA_ID, payload.toByteArray()));
        }
        return out;
    }

    private byte[] readBlock(Request request) throws IOException {
        long offset = request.pieceIndex() * meta.pieceLength() + request.begin();
        ByteBuffer buffer = ByteBuffer.allocate(request.length());
        while (buffer.hasRemaining()) {
            if (content.read(buffer, offset + buffer.position()) < 0) {
                throw new IOException("unexpected eof at " + (offset + buffer.position()));
            }
        }
        return buffer.array();
    }

    @Override
    public void close() {
        transport.close();
        try {
            content.close();
        } catch (IOException ignored) {
        }
    }

}
