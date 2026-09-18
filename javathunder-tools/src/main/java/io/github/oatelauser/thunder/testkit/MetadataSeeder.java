package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import io.github.oatelauser.thunder.core.internal.engine.HashExchange;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.core.internal.peer.transport.NioTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.peer.PeerIds;
import io.github.oatelauser.thunder.core.internal.wire.ExtendedMessage;
import io.github.oatelauser.thunder.core.internal.wire.HashRequest;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import io.github.oatelauser.thunder.core.internal.wire.PieceMessage;
import io.github.oatelauser.thunder.core.internal.wire.Request;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.nio.file.StandardOpenOption.READ;

/**
 * 支持 BEP 10/9/52 的测试种子方：应答扩展握手（声明 ut_metadata + metadata_size）、
 * ut_metadata 分块请求（msg_type=1 + 16KiB data）与 BEP 52 hash request（层带 +
 * Merkle 证明），同时照常供数据下载。需要提供 .torrent 的完整原始字节（info 字典
 * 从中切出）。
 */
public final class MetadataSeeder implements AutoCloseable {

    private static final int METADATA_BLOCK = 16 * 1024;
    private static final int UT_METADATA_ID = 1; // 我们侧的子 ID，客户端按 m 字典对端值回发

    private final NioTransport transport;
    private final TorrentMetadata meta;
    private final byte[] infoDict;
    private final FileChannel content;

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
        byte[] info = TorrentParser.extractInfoDict(torrentBytes);
        NioTransport transport = new NioTransport(PeerIds.generate());
        MetadataSeeder seeder = new MetadataSeeder(transport, meta, info, FileChannel.open(contentFile, READ));
        transport.listen(0, infoHash -> Arrays.equals(infoHash, meta.infoHash()) ? seeder.handler() : null);
        return seeder;
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
                        } else if (message instanceof HashRequest request) {
                            // BEP 52：piece-layer 哈希带按证明供出（与引擎供种侧同源）
                            responses.add(HashExchange.respond(meta, request));
                        } else if (message instanceof Request request) {
                            try {
                                responses.add(new PieceMessage(request.pieceIndex(), request.begin(),
                                        SeederCore.readBlock(content, meta, request)));
                            } catch (IOException e) {
                                return;
                            }
                        }
                    }
                    channel.write(responses);
                });
                for (PeerWireMessage hello : SeederCore.seederHello(meta)) {
                    channel.write(hello);
                }
            }

            @Override
            public void onConnectFailed(InetSocketAddress address, @Nullable Throwable cause) {
            }
        };
    }

    private List<PeerWireMessage> handleExtended(ExtendedMessage ext) {
        List<PeerWireMessage> out = new ArrayList<>();
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
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
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

    @Override
    public void close() {
        transport.close();
        try {
            content.close();
        } catch (IOException ignored) {
        }
    }

}
