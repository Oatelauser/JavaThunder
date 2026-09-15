package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.peer.transport.NioTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import io.github.oatelauser.thunder.core.internal.tracker.PeerIds;
import io.github.oatelauser.thunder.core.internal.wire.BitfieldMessage;
import io.github.oatelauser.thunder.core.internal.wire.PieceMessage;
import io.github.oatelauser.thunder.core.internal.wire.Request;
import io.github.oatelauser.thunder.core.internal.wire.Unchoke;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * NIO 版种子方（探针对端）：与引擎同构的事件循环——批量读请求帧、gather 批量写。
 * 用于对称性能测量，消除 FakeSeeder 逐帧阻塞的天花板。
 */
public final class NioSeeder implements AutoCloseable {

    private final NioTransport transport;
    private final byte[] content;
    private final TorrentMetadata meta;

    private NioSeeder(NioTransport transport, byte[] content, TorrentMetadata meta) {
        this.transport = transport;
        this.content = content;
        this.meta = meta;
    }

    public static NioSeeder start(Path contentFile, TorrentMetadata meta) throws IOException {
        NioTransport transport = new NioTransport(PeerIds.generate());
        NioSeeder seeder = new NioSeeder(transport, Files.readAllBytes(contentFile), meta);
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
                    java.util.List<io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage> responses =
                        new java.util.ArrayList<>(messages.size());
                    for (io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage message : messages) {
                        if (message instanceof Request request) {
                            responses.add(new PieceMessage(request.pieceIndex(), request.begin(),
                                readBlock(request)));
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
            public void onConnectFailed(java.net.InetSocketAddress address, @Nullable Throwable cause) {
            }
        };
    }

    private byte[] readBlock(Request request) {
        int offset = (int) (request.pieceIndex() * meta.pieceLength()) + request.begin();
        return Arrays.copyOfRange(content, offset, offset + request.length());
    }

    @Override
    public void close() {
        transport.close();
    }
}
