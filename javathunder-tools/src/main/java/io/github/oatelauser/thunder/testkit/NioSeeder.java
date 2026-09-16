package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import io.github.oatelauser.thunder.core.internal.peer.transport.NioTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.peer.PeerIds;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import io.github.oatelauser.thunder.core.internal.wire.PieceMessage;
import io.github.oatelauser.thunder.core.internal.wire.Request;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
                    List<PeerWireMessage> responses = new ArrayList<>(messages.size());
                    for (PeerWireMessage message : messages) {
                        if (message instanceof Request request) {
                            try {
                                responses.add(new PieceMessage(request.pieceIndex(), request.begin(),
                                        SeederCore.readBlock(content, meta, request)));
                            } catch (IOException e) {
                                return; // 源文件不可读：直接放弃本批
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

    @Override
    public void close() {
        transport.close();
        try {
            content.close();
        } catch (IOException ignored) {
        }
    }

}
