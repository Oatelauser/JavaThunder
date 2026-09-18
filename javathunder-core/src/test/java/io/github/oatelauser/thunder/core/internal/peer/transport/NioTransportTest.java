package io.github.oatelauser.thunder.core.internal.peer.transport;

import io.github.oatelauser.thunder.core.internal.wire.Handshake;
import io.github.oatelauser.thunder.core.internal.wire.Interested;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireCodec;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import io.github.oatelauser.thunder.core.internal.wire.PieceMessage;
import io.github.oatelauser.thunder.core.internal.wire.Request;
import io.github.oatelauser.thunder.core.internal.wire.Unchoke;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 脚本化假对端验证 NioTransport 的出站/入站握手与消息收发。 */
class NioTransportTest {

    private static final byte[] INFO_HASH = new byte[20];
    private static final byte[] OWN_PEER_ID = "-JT0001-niotrans-t01".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FAKE_PEER_ID = "-FAKE01-niotrans-t01".getBytes(StandardCharsets.US_ASCII);

    static {
        Arrays.fill(INFO_HASH, (byte) 5);
    }

    private static void readFully(InputStream in, byte[] target) throws IOException {
        int off = 0;
        while (off < target.length) {
            int n = in.read(target, off, target.length - off);
            if (n < 0) {
                throw new IOException("eof");
            }
            off += n;
        }
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        byte[] header = new byte[4];
        readFully(in, header);
        int length = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
            | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] payload = new byte[length];
        readFully(in, payload);
        byte[] frame = new byte[4 + length];
        System.arraycopy(header, 0, frame, 0, 4);
        System.arraycopy(payload, 0, frame, 4, length);
        return frame;
    }

    @Test
    void oversizedFrameDeclarationClosesConnectionWithoutAllocation() throws Exception {
        // 恶意对端只发 4 字节前缀声明 0x7FFFFFFF 帧长：必须在扩容/解码之前断连
        // （前置校验）——若引擎误扩容等完整帧，假对端永远等不到 EOF，测试超时失败
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             NioTransport transport = new NioTransport(OWN_PEER_ID)) {
            CompletableFuture<PeerChannel> connected = new CompletableFuture<>();
            CompletableFuture<Throwable> closed = new CompletableFuture<>();

            Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    byte[] handshake = new byte[68];
                    readFully(in, handshake);
                    out.write(Handshake.encode(INFO_HASH, FAKE_PEER_ID));
                    out.flush();
                    out.write(new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
                    out.flush();
                    int eof = in.read();
                    assertTrue(eof < 0, "引擎应以断连回应超限帧声明，而非等待载荷");
                } catch (IOException ignored) {
                }
            });

            transport.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), INFO_HASH,
                new TransportHandler() {
                    @Override
                    public void onConnected(PeerChannel channel) {
                        channel.setCloseListener(closed::complete);
                        connected.complete(channel);
                    }

                    @Override
                    public void onConnectFailed(InetSocketAddress address, Throwable cause) {
                        connected.completeExceptionally(cause);
                    }
                });

            connected.get(5, TimeUnit.SECONDS);
            Throwable cause = closed.get(5, TimeUnit.SECONDS);
            assertTrue(cause != null && cause.getMessage() != null
                    && cause.getMessage().contains("exceeds limit"), "close 原因应为帧超限：" + cause);
        }
    }

    @Test
    void outboundHandshakeAndMessageRoundTrip() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             NioTransport transport = new NioTransport(OWN_PEER_ID)) {
            CompletableFuture<PeerChannel> connected = new CompletableFuture<>();
            CompletableFuture<PeerWireMessage> received = new CompletableFuture<>();

            Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    byte[] handshake = new byte[68];
                    readFully(in, handshake);
                    if (!Arrays.equals(Handshake.decode(handshake).infoHash(), INFO_HASH)) {
                        return;
                    }
                    out.write(Handshake.encode(INFO_HASH, FAKE_PEER_ID));
                    out.flush();
                    // 收 client 的 Interested 帧，回 Unchoke + Piece
                    byte[] frame = readFrame(in);
                    assertTrue(frame.length == 5 && (frame[4] & 0xFF) == 2, "expect interested");
                    out.write(PeerWireCodec.encode(Unchoke.INSTANCE));
                    out.write(PeerWireCodec.encode(
                        new PieceMessage(3, 8192, new byte[]{7, 7, 7})));
                    out.flush();
                } catch (IOException ignored) {
                }
            });

            transport.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), INFO_HASH,
                new TransportHandler() {
                    @Override
                    public void onConnected(PeerChannel channel) {
                        channel.setMessageListener(batch -> received.complete(batch.get(0)));
                        channel.write(Interested.INSTANCE);
                        connected.complete(channel);
                    }

                    @Override
                    public void onConnectFailed(InetSocketAddress address, Throwable cause) {
                        connected.completeExceptionally(cause);
                    }
                });

            PeerChannel channel = connected.get(5, TimeUnit.SECONDS);
            PeerWireMessage message = received.get(5, TimeUnit.SECONDS);
            assertTrue(message instanceof Unchoke);
            assertTrue(connected.get(5, TimeUnit.SECONDS) != null);
            channel.close();
        }
    }

    @Test
    void inboundRoutingRejectsUnknownInfoHash() throws Exception {
        try (NioTransport transport = new NioTransport(OWN_PEER_ID)) {
            transport.listen(0, infoHash -> null); // 路由一律拒收

            CountDownLatch closedByServer = new CountDownLatch(1);
            AtomicReference<byte[]> seenReply = new AtomicReference<>();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", transport.listeningPort()), 3000);
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                out.write(Handshake.encode(INFO_HASH, FAKE_PEER_ID));
                out.flush();
                InputStream in = socket.getInputStream();
                byte[] maybe = new byte[68];
                int n = in.read(maybe); // 服务器应直接关闭：读到 EOF 或部分数据后 EOF
                seenReply.set(maybe);
                if (n < 0) {
                    closedByServer.countDown();
                } else if (in.read() < 0) {
                    closedByServer.countDown();
                }
            }
            assertTrue(closedByServer.await(2, TimeUnit.SECONDS), "server must close unknown-info-hash inbound");
        }
    }

    @Test
    void inboundRoutingAcceptsKnownInfoHash() throws Exception {
        byte[] ownPeerId = OWN_PEER_ID;
        try (NioTransport transport = new NioTransport(ownPeerId)) {
            CompletableFuture<PeerChannel> inbound = new CompletableFuture<>();
            CompletableFuture<PeerWireMessage> fromClient = new CompletableFuture<>();
            transport.listen(0, infoHash -> {
                if (!Arrays.equals(infoHash, INFO_HASH)) {
                    return null;
                }
                return new TransportHandler() {
                    @Override
                    public void onConnected(PeerChannel channel) {
                        channel.setMessageListener(batch -> fromClient.complete(batch.get(0)));
                        inbound.complete(channel);
                    }

                    @Override
                    public void onConnectFailed(InetSocketAddress address, Throwable cause) {
                        inbound.completeExceptionally(cause);
                    }
                };
            });

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", transport.listeningPort()), 3000);
                socket.setSoTimeout(5000);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                out.write(Handshake.encode(INFO_HASH, FAKE_PEER_ID));
                out.flush();
                byte[] reply = new byte[68];
                readFully(in, reply);
                assertArrayEquals(OWN_PEER_ID, Handshake.decode(reply).peerId());
                out.write(PeerWireCodec.encode(
                    new Request(1, 0, 16384)));
                out.flush();
            }

            PeerWireMessage message = fromClient.get(5, TimeUnit.SECONDS);
            assertTrue(message instanceof Request);
            inbound.get(5, TimeUnit.SECONDS).close();
        }
    }

    @Test
    void batchedWritesArriveInOrder() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             NioTransport transport = new NioTransport(OWN_PEER_ID)) {
            CompletableFuture<PeerChannel> connected = new CompletableFuture<>();
            CountDownLatch done = new CountDownLatch(1);
            List<PeerWireMessage> seen = new CopyOnWriteArrayList<>();

            Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    byte[] handshake = new byte[68];
                    readFully(in, handshake);
                    out.write(Handshake.encode(INFO_HASH, FAKE_PEER_ID));
                    out.flush();
                    for (int i = 0; i < 100; i++) {
                        PeerWireMessage message = PeerWireCodec
                            .decodeFrame(ByteBuffer.wrap(readFrame(in)));
                        seen.add(message);
                    }
                    done.countDown();
                } catch (IOException ignored) {
                }
            });

            transport.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), INFO_HASH,
                new TransportHandler() {
                    @Override
                    public void onConnected(PeerChannel channel) {
                        // 一次性排 100 条消息：验证 gather 批刷的顺序保持
                        for (int i = 0; i < 100; i++) {
                            channel.write(new Request(i, 0, 16384));
                        }
                        connected.complete(channel);
                    }

                    @Override
                    public void onConnectFailed(InetSocketAddress address, Throwable cause) {
                        connected.completeExceptionally(cause);
                    }
                });
            connected.get(5, TimeUnit.SECONDS);
            assertTrue(done.await(10, TimeUnit.SECONDS), "all 100 messages should arrive");
            for (int i = 0; i < 100; i++) {
                assertTrue(seen.get(i) instanceof Request r && r.pieceIndex() == i,
                    "order broken at " + i);
            }
        }
    }
}
