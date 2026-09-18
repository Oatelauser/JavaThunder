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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 脚本化假对端直测 BlockingTransport 参照实现（ADR-0003）：握手路由、消息收发与 close 清理。 */
class BlockingTransportTest {

    private static final byte[] INFO_HASH = new byte[20];
    private static final byte[] OWN_PEER_ID = "-JT0001-blocktr-t001".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FAKE_PEER_ID = "-FAKE01-blocktr-t001".getBytes(StandardCharsets.US_ASCII);

    static {
        Arrays.fill(INFO_HASH, (byte) 5);
    }

    /** 假对端脚本：拿到已 accept 的 socket 流做编排。 */
    private interface PeerScript {
        void run(InputStream in, OutputStream out) throws IOException;
    }

    private static void startFakePeer(ServerSocket server, PeerScript script) {
        Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(10_000);
                script.run(new BufferedInputStream(socket.getInputStream()),
                    socket.getOutputStream());
            } catch (IOException ignored) {
            }
        });
    }

    /** 记录型回调：通道就绪 / 首条消息 / 关闭原因。 */
    private static final class RecordingHandler implements TransportHandler {
        final CompletableFuture<PeerChannel> channel = new CompletableFuture<>();
        final CompletableFuture<PeerWireMessage> firstMessage = new CompletableFuture<>();
        final CompletableFuture<Throwable> closed = new CompletableFuture<>();

        @Override
        public void onConnected(PeerChannel peerChannel) {
            peerChannel.setMessageListener(batch -> firstMessage.complete(batch.get(0)));
            peerChannel.setCloseListener(closed::complete);
            channel.complete(peerChannel);
        }

        @Override
        public void onConnectFailed(InetSocketAddress address, Throwable cause) {
            channel.completeExceptionally(cause);
        }
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
    void outboundHandshakeAndMessageRoundTrip() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             BlockingTransport transport = new BlockingTransport(OWN_PEER_ID)) {
            startFakePeer(server, (in, out) -> {
                byte[] handshake = new byte[68];
                readFully(in, handshake);
                if (!Arrays.equals(Handshake.decode(handshake).infoHash(), INFO_HASH)) {
                    return;
                }
                out.write(Handshake.encode(INFO_HASH, FAKE_PEER_ID));
                out.flush();
                byte[] frame = readFrame(in);
                assertTrue(frame.length == 5 && (frame[4] & 0xFF) == 2, "expect interested");
                out.write(PeerWireCodec.encode(Unchoke.INSTANCE));
                out.write(PeerWireCodec.encode(
                    new PieceMessage(3, 8192, new byte[]{7, 7, 7})));
                out.flush();
                // 等客户端先关再退出：否则服务端 EOF 可能抢在本地 close 之前到达
                // readLoop，close 原因变成 IOException 而非 null——Linux CI 跑步者上
                // 确定性复现的测试竞态（JDK 21/25/26 三腿全挂；closeWith 幂等保证
                // 本地 close 先到即胜出，读循环其后的异常被吞）
                while (in.read() != -1) {
                }
            });

            RecordingHandler handler = new RecordingHandler();
            transport.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()),
                INFO_HASH, handler);

            PeerChannel channel = handler.channel.get(5, TimeUnit.SECONDS);
            assertArrayEquals(FAKE_PEER_ID, channel.remotePeerId());
            channel.write(Interested.INSTANCE);
            PeerWireMessage message = handler.firstMessage.get(5, TimeUnit.SECONDS);
            assertTrue(message instanceof Unchoke);
            channel.close();
            assertNull(handler.closed.get(5, TimeUnit.SECONDS)); // 本端主动 close：cause 为 null
        }
    }

    @Test
    void inboundRoutingRejectsUnknownInfoHash() throws Exception {
        try (BlockingTransport transport = new BlockingTransport(OWN_PEER_ID)) {
            transport.listen(0, infoHash -> null); // 路由一律拒收

            CountDownLatch closedByServer = new CountDownLatch(1);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", transport.listeningPort()), 3000);
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                out.write(Handshake.encode(INFO_HASH, FAKE_PEER_ID));
                out.flush();
                InputStream in = socket.getInputStream();
                byte[] maybe = new byte[68];
                int n = in.read(maybe); // 服务器应直接关闭：读到 EOF 或部分数据后 EOF
                if (n < 0 || in.read() < 0) {
                    closedByServer.countDown();
                }
            }
            assertTrue(closedByServer.await(2, TimeUnit.SECONDS),
                "server must close unknown-info-hash inbound");
        }
    }

    @Test
    void inboundRoutingAcceptsKnownInfoHash() throws Exception {
        try (BlockingTransport transport = new BlockingTransport(OWN_PEER_ID)) {
            RecordingHandler handler = new RecordingHandler();
            transport.listen(0, infoHash ->
                Arrays.equals(infoHash, INFO_HASH) ? handler : null);
            assertTrue(transport.listeningPort() > 0);

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
                out.write(PeerWireCodec.encode(new Request(1, 0, 16384)));
                out.flush();
            }

            PeerWireMessage message = handler.firstMessage.get(5, TimeUnit.SECONDS);
            assertTrue(message instanceof Request);
            handler.channel.get(5, TimeUnit.SECONDS).close();
        }
    }

    @Test
    void writesArriveInOrderAndCloseSignalsPeerEof() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             BlockingTransport transport = new BlockingTransport(OWN_PEER_ID)) {
            CountDownLatch done = new CountDownLatch(1);
            CountDownLatch eof = new CountDownLatch(1);
            List<PeerWireMessage> seen = new CopyOnWriteArrayList<>();
            startFakePeer(server, (in, out) -> {
                byte[] handshake = new byte[68];
                readFully(in, handshake);
                out.write(Handshake.encode(INFO_HASH, FAKE_PEER_ID));
                out.flush();
                for (int i = 0; i < 100; i++) {
                    seen.add(PeerWireCodec.decodeFrame(ByteBuffer.wrap(readFrame(in))));
                }
                done.countDown();
                if (in.read() == -1) { // 客户端 close 后应观察到 EOF
                    eof.countDown();
                }
            });

            RecordingHandler handler = new RecordingHandler();
            transport.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()),
                INFO_HASH, handler);
            PeerChannel channel = handler.channel.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 100; i++) {
                channel.write(new Request(i, 0, 16384));
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "all 100 messages should arrive");
            for (int i = 0; i < 100; i++) {
                assertTrue(seen.get(i) instanceof Request r && r.pieceIndex() == i,
                    "order broken at " + i);
            }
            channel.close();
            assertNull(handler.closed.get(5, TimeUnit.SECONDS));
            assertTrue(eof.await(5, TimeUnit.SECONDS), "peer should see EOF after close");
        }
    }

    /**
     * transport.close() 必须关闭已建连接（对齐 NioTransport 语义）：修复前只关
     * 监听 socket，已连接通道的读虚拟线程滞留在阻塞 read 上，直到对端断开或
     * 120s 读超时。假对端握完手后干等：close 后应以本地关闭（cause=null）通知
     * 通道，且对端观察到 EOF。
     */
    @Test
    void transportCloseClosesEstablishedChannels() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             BlockingTransport transport = new BlockingTransport(OWN_PEER_ID)) {
            CountDownLatch peerEof = new CountDownLatch(1);
            startFakePeer(server, (in, out) -> {
                byte[] handshake = new byte[68];
                readFully(in, handshake);
                out.write(Handshake.encode(INFO_HASH, FAKE_PEER_ID));
                out.flush();
                if (in.read() == -1) { // 阻塞至引擎关闭连接
                    peerEof.countDown();
                }
            });

            RecordingHandler handler = new RecordingHandler();
            transport.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()),
                INFO_HASH, handler);
            handler.channel.get(5, TimeUnit.SECONDS); // 通道就绪且不主动关闭

            transport.close();
            assertNull(handler.closed.get(5, TimeUnit.SECONDS),
                "transport.close() 应以本地关闭（cause=null）通知已建通道");
            assertTrue(peerEof.await(5, TimeUnit.SECONDS), "对端应观察到 EOF");
        }
    }
}
