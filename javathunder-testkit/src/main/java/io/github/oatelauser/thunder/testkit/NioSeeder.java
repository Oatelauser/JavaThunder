package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.peer.transport.NioTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import io.github.oatelauser.thunder.core.internal.tracker.PeerIds;
import io.github.oatelauser.thunder.core.internal.wire.BitfieldMessage;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import io.github.oatelauser.thunder.core.internal.wire.PieceMessage;
import io.github.oatelauser.thunder.core.internal.wire.Request;
import io.github.oatelauser.thunder.core.internal.wire.Unchoke;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * NIO 版种子方（探针对端）：与引擎同构的事件循环——批量读请求帧、gather 批量写。
 * 内容按需从文件分块读（D2：内存占用与文件大小无关，支持 GiB 级 payload 测试）。
 */
public final class NioSeeder implements AutoCloseable {

    private final NioTransport transport;
    private final TorrentMetadata meta;
    private final FileChannel content;

    private NioSeeder(NioTransport transport, FileChannel content, TorrentMetadata meta) {
        this.transport = transport;
        this.content = content;
        this.meta = meta;
    }

    public static NioSeeder start(Path contentFile, TorrentMetadata meta) throws IOException {
        NioTransport transport = new NioTransport(PeerIds.generate());
        NioSeeder seeder = new NioSeeder(transport,
            FileChannel.open(contentFile, StandardOpenOption.READ), meta);
        transport.listen(0, infoHash ->
            Arrays.equals(infoHash, meta.infoHash()) ? seeder.handler() : null);
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
                    java.util.List<PeerWireMessage> responses = new java.util.ArrayList<>(messages.size());
                    for (PeerWireMessage message : messages) {
                        if (message instanceof Request request) {
                            try {
                                responses.add(new PieceMessage(request.pieceIndex(), request.begin(),
                                    readBlock(request)));
                            } catch (IOException e) {
                                return; // 源文件不可读：直接放弃本批
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

    /** 分块读：位置式 channel 读，16KiB 粒度。 */
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
