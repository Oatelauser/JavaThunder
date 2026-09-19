package io.github.oatelauser.thunder.core.internal.tracker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEP 15 对拍：脚本化 UDP tracker 应答 connect/announce，验证两段事务、紧凑 peer
 * 解析、并发事务按 tid 分发路由、action=3 的重连语义与 close 生命周期。
 */
class UdpTrackerClientTest {

    /** 模拟服务端已过期/新鲜的 connection_id（serveStaleThenFresh 等假 tracker 用）。 */
    private static final long STALE_CONNECTION_ID = 0x1111111111111111L;
    private static final long FRESH_CONNECTION_ID = 0x2222222222222222L;

    private final AtomicInteger connectCount = new AtomicInteger();
    private volatile boolean running = true;
    private final AtomicReference<byte[]> lastPeerBytes = new AtomicReference<>();
    /** 服务线程内的请求体断言失败经此传回主线程（异步断言不直接抛）。 */
    private final AtomicReference<AssertionError> requestShapeError = new AtomicReference<>();

    @AfterEach
    void stop() {
        running = false;
    }

    @Test
    void connectThenAnnounceWithCompactPeers() throws Exception {
        try (DatagramSocket server = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             UdpTrackerClient client = new UdpTrackerClient()) {
            Thread.ofVirtual().start(() -> serve(server));

            byte[] infoHash = new byte[20];
            byte[] peerId = "-JT0001-udptest00001".getBytes(StandardCharsets.US_ASCII);
            AnnounceRequest request = new AnnounceRequest(infoHash, peerId, 6881,
                100, 200, 300, TrackerEvent.STARTED, 10);
            AnnounceResponse response = client.announce(
                "udp://127.0.0.1:" + server.getLocalPort() + "/announce", request);

            assertEquals(1800, response.interval());
            assertEquals(3, response.seeders());
            assertEquals(1, response.leechers());
            assertEquals(2, response.peers().size());
            assertEquals(7777, response.peers().get(0).getPort());
            assertEquals(8888, response.peers().get(1).getPort());
            assertEquals(1, connectCount.get(), "connection_id 应被缓存复用（单次 announce 只 connect 一次）");
            AssertionError shape = requestShapeError.get();
            if (shape != null) {
                throw shape; // 服务线程捕到的请求体字段断言失败在此抛出
            }

            // 同一 client 二次 announce：命中缓存，不再 connect
            client.announce("udp://127.0.0.1:" + server.getLocalPort() + "/announce", request);
            assertEquals(1, connectCount.get());
        }
    }

    /**
     * 请求体逐字段断言（BEP 15 标准偏移）：曾把 left/uploaded 写反、port 写成 int
     * 且尾部 6 字节未写满——真实 tracker 会解出 num_want=0/port=0。值取
     * 200/300/100（downloaded/left/uploaded）互异以捕捉换位。
     */
    private void assertAnnounceRequestShape(ByteBuffer in, int packetLength) {
        try {
            assertEquals(98, packetLength, "announce 请求应恰为 98 字节");
            assertEquals(200L, in.getLong(56), "downloaded@56");
            assertEquals(300L, in.getLong(64), "left@64");
            assertEquals(100L, in.getLong(72), "uploaded@72");
            assertEquals(2, in.getInt(80), "event@80（started=2）");
            assertEquals(10, in.getInt(92), "num_want@92");
            assertEquals(6881, in.getShort(96) & 0xFFFF, "port@96（2 字节大端）");
        } catch (AssertionError e) {
            requestShapeError.compareAndSet(null, e);
        }
    }

    private void serve(DatagramSocket server) {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                server.setSoTimeout(200);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            server.receive(packet);
            ByteBuffer in = ByteBuffer.wrap(packet.getData(), 0, packet.getLength())
                .order(ByteOrder.BIG_ENDIAN);
            // BEP 15 请求头：8 字节 connection_id/protocol_id 前缀，action 在偏移 8、tid 在 12
            int action = in.getInt(8);
            int transactionId = in.getInt(12);
                ByteBuffer out;
                if (action == 0) {
                    connectCount.incrementAndGet();
                    out = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                        .putInt(0).putInt(transactionId).putLong(0x1234567890ABCDEFL);
                } else if (action == 1) {
                    assertAnnounceRequestShape(in, packet.getLength());
                    byte[] peers = {127, 0, 0, 1, 0x1E, 0x61, 127, 0, 0, 2, 0x22, (byte) 0xB8};
                    lastPeerBytes.set(peers);
                    out = ByteBuffer.allocate(20 + peers.length).order(ByteOrder.BIG_ENDIAN)
                        .putInt(1).putInt(transactionId)
                        .putInt(1800)   // interval
                        .putInt(1)      // leechers（BEP 15 顺序：interval/leechers/seeders）
                        .putInt(3);     // seeders
                    out.put(peers);
                } else {
                    continue;
                }
                byte[] wire = out.array();
                server.send(new DatagramPacket(wire, wire.length,
                    packet.getAddress(), packet.getPort()));
            } catch (SocketTimeoutException ignored) {
                // 检查 running
            } catch (Exception ignored) {
                return;
            }
        }
    }

    @Test
    void supportsOnlyUdpScheme() {
        assertTrue(UdpTrackerClient.supports("udp://t:6969/announce"));
        assertTrue(!UdpTrackerClient.supports("http://t/announce"));
    }

    /**
     * 恶意/损坏 tracker 回 tid 匹配但截短的 connect 应答（12 &lt; 16 字节）：
     * 修复前 getLong(8) 抛 IndexOutOfBoundsException（RuntimeException）穿透
     * announce——调用方只捕获 TrackerException；修复后按本轮失败退避重试，
     * 最终以 TrackerException 收场而非越界异常。
     */
    @Test
    void truncatedConnectResponseFailsAsTrackerExceptionNotOutOfBounds() throws Exception {
        try (DatagramSocket server = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             UdpTrackerClient client = new UdpTrackerClient()) {
            Thread.ofVirtual().start(() -> serveTruncatedConnect(server));
            assertThrows(TrackerException.class, () -> client.announce(
                "udp://127.0.0.1:" + server.getLocalPort() + "/announce", sampleRequest(10)));
        }
    }

    /**
     * 并发两路 announce 应真并行且应答按事务 ID 正确路由：假 tracker 扣住先到的
     * announce，直到两路 numwant 互异的请求同时在途才一并应答——若客户端仍在共享
     * socket 上串行化（旧 synchronized exchange 语义），第二路永远无法在途，第一路
     * 只会超时重传（numwant 不变，屏障永不满足）。应答的 peer 端口按 numwant 编码
     * （20000+100n+i），两路各自拿到自己的数据证明应答无串扰。
     */
    @Test
    void concurrentAnnouncesRunInParallelAndRouteByTransactionId() throws Exception {
        try (DatagramSocket server = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             UdpTrackerClient client = new UdpTrackerClient()) {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();
            Thread.ofVirtual().start(() -> serveParallelAnnounces(server, inFlight, maxInFlight));
            String url = "udp://127.0.0.1:" + server.getLocalPort() + "/announce";
            AtomicReference<AnnounceResponse> first = new AtomicReference<>();
            AtomicReference<AnnounceResponse> second = new AtomicReference<>();
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            Thread a = Thread.ofVirtual().start(() -> runAnnounce(client, url, 1, first, failure));
            Thread b = Thread.ofVirtual().start(() -> runAnnounce(client, url, 2, second, failure));
            a.join(20_000);
            b.join(20_000);
            assertNull(failure.get(), "并发 announce 应全部成功（观测最大在途=" + maxInFlight.get() + "）");
            assertTrue(maxInFlight.get() >= 2,
                "两路 announce 应同时在途（收发分离恢复并行），观测最大在途=" + maxInFlight.get());
            assertEquals(1, first.get().peers().size(), "numwant=1 的一路应路由到自己的应答");
            assertEquals(20100, first.get().peers().get(0).getPort());
            assertEquals(2, second.get().peers().size(), "numwant=2 的一路应路由到自己的应答");
            assertEquals(20200, second.get().peers().get(0).getPort());
            assertEquals(20201, second.get().peers().get(1).getPort());
        }
    }

    /**
     * action=3 对拍（与服务端新增的 connect 校验对齐）：首个 connection_id 被服务端
     * 判过期，announce 回 error；客户端应清除该地址的连接缓存、重新 connect（换新
     * id）并重发 announce 成功——整体重试恰好一次。
     */
    @Test
    void staleConnectionIdErrorTriggersReconnectAndRetrySucceeds() throws Exception {
        try (DatagramSocket server = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             UdpTrackerClient client = new UdpTrackerClient()) {
            Thread.ofVirtual().start(() -> serveStaleThenFresh(server));
            AnnounceResponse response = client.announce(
                "udp://127.0.0.1:" + server.getLocalPort() + "/announce", sampleRequest(10));
            assertEquals(1800, response.interval());
            assertEquals(1, response.peers().size());
            assertEquals(20001, response.peers().get(0).getPort());
            assertEquals(2, connectCount.get(),
                "收到 stale error 后应清除缓存整体重连一次（恰好两次 connect）");
        }
    }

    /** tracker 对新旧 connection_id 一律回 error：客户端只允许重连一次，随后必须上抛。 */
    @Test
    void announceErrorRetriesOnlyOnceThenFails() throws Exception {
        try (DatagramSocket server = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             UdpTrackerClient client = new UdpTrackerClient()) {
            Thread.ofVirtual().start(() -> serveAlwaysErrorAnnounce(server));
            TrackerException failure = assertThrows(TrackerException.class, () -> client.announce(
                "udp://127.0.0.1:" + server.getLocalPort() + "/announce", sampleRequest(10)));
            assertTrue(failure.getMessage().contains("connection still stale"),
                "tracker 的 error 文本应透出给调用方: " + failure.getMessage());
            assertEquals(2, connectCount.get(), "重连一次后仍报错必须上抛，不得循环重连");
        }
    }

    /**
     * close() 语义：在途 announce 应立即以异常返回（future 被异常完成）而非等满
     * 5s 响应超时；close 可重复调用（幂等）。
     */
    @Test
    void closeFailsInFlightAnnouncePromptlyAndIsIdempotent() throws Exception {
        try (DatagramSocket server = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            CountDownLatch announceHeld = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> serveConnectOnly(server, announceHeld));
            UdpTrackerClient client = new UdpTrackerClient();
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    client.announce(
                        "udp://127.0.0.1:" + server.getLocalPort() + "/announce", sampleRequest(10));
                } catch (RuntimeException e) {
                    failure.compareAndSet(null, e);
                }
            });
            assertTrue(announceHeld.await(5, TimeUnit.SECONDS), "假 tracker 应观测到在途 announce");
            client.close();
            client.close(); // 幂等：重复 close 不得抛异常
            caller.join(3_000);
            assertTrue(!caller.isAlive(), "close 后在途 announce 应立即以异常返回，不悬挂");
            assertTrue(failure.get() instanceof TrackerException,
                "在途事务应以 TrackerException 失败，实际=" + failure.get());
        }
    }

    private static AnnounceRequest sampleRequest(int numwant) {
        return new AnnounceRequest(new byte[20], "-JT0001-udptest00001".getBytes(StandardCharsets.US_ASCII),
            6881, 100, 200, 300, TrackerEvent.STARTED, numwant);
    }

    private static void runAnnounce(UdpTrackerClient client, String url, int numwant,
            AtomicReference<AnnounceResponse> into, AtomicReference<RuntimeException> failure) {
        try {
            into.set(client.announce(url, sampleRequest(numwant)));
        } catch (RuntimeException e) {
            failure.compareAndSet(null, e);
        }
    }

    /** 只回 tid 匹配的 12 字节截短 connect 应答（connection_id 只带 4 字节）。 */
    private void serveTruncatedConnect(DatagramSocket server) {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                server.setSoTimeout(200);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                server.receive(packet);
                ByteBuffer in = ByteBuffer.wrap(packet.getData(), 0, packet.getLength())
                    .order(ByteOrder.BIG_ENDIAN);
                if (in.getInt(8) != 0) {
                    continue; // 只截短 connect；announce 不应发生（connect 已失败）
                }
                byte[] wire = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                    .putInt(0).putInt(in.getInt(12)).putInt(0x12345678).array();
                server.send(new DatagramPacket(wire, wire.length,
                    packet.getAddress(), packet.getPort()));
            } catch (SocketTimeoutException ignored) {
                // 检查 running
            } catch (Exception ignored) {
                return;
            }
        }
    }

    /**
     * 并行对拍的假 tracker：connect 即时应答；announce 扣住直到两路 numwant 互异的
     * 请求都已在途（屏障）才应答。注意须把请求字节快照后再交给延迟应答线程——
     * 接收缓冲区会被后续请求复用。
     */
    private void serveParallelAnnounces(DatagramSocket server,
            AtomicInteger inFlight, AtomicInteger maxInFlight) {
        Set<Integer> numwantArrived = ConcurrentHashMap.newKeySet();
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                server.setSoTimeout(200);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                server.receive(packet);
                ByteBuffer in = ByteBuffer.wrap(packet.getData(), 0, packet.getLength())
                    .order(ByteOrder.BIG_ENDIAN);
                if (in.getInt(8) == 0) {
                    byte[] wire = connectReply(in.getInt(12), FRESH_CONNECTION_ID);
                    server.send(new DatagramPacket(wire, wire.length,
                        packet.getAddress(), packet.getPort()));
                } else {
                    int numwant = in.getInt(92);
                    numwantArrived.add(numwant);
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                    SocketAddress replyTo = packet.getSocketAddress();
                    Thread.ofVirtual().start(() ->
                        respondBehindBarrier(server, request, replyTo, numwant, numwantArrived, inFlight));
                }
            } catch (SocketTimeoutException ignored) {
                // 检查 running
            } catch (Exception ignored) {
                return;
            }
        }
    }

    /** 屏障：等第二路 numwant 互异的 announce 在途后应答（10s 上限防测试挂死）。 */
    private static void respondBehindBarrier(DatagramSocket server, byte[] request,
            SocketAddress replyTo, int numwant, Set<Integer> numwantArrived, AtomicInteger inFlight) {
        try {
            long deadline = System.currentTimeMillis() + 10_000;
            while (numwantArrived.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            int transactionId = ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN).getInt(12);
            byte[] peers = new byte[6 * numwant];
            for (int i = 0; i < numwant; i++) {
                int port = 20_000 + 100 * numwant + i;
                peers[6 * i] = 127;
                peers[6 * i + 4] = (byte) (port >> 8);
                peers[6 * i + 5] = (byte) port;
            }
            byte[] wire = ByteBuffer.allocate(20 + peers.length).order(ByteOrder.BIG_ENDIAN)
                .putInt(1).putInt(transactionId)
                .putInt(1800).putInt(1).putInt(3)
                .put(peers).array();
            server.send(new DatagramPacket(wire, wire.length, replyTo));
        } catch (Exception ignored) {
            // 客户端已关闭等情形：忽略
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** 首次 connect 发 STALE id，重连发 FRESH id；STALE id 的 announce 回 error，FRESH 正常。 */
    private void serveStaleThenFresh(DatagramSocket server) {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                server.setSoTimeout(200);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                server.receive(packet);
                ByteBuffer in = ByteBuffer.wrap(packet.getData(), 0, packet.getLength())
                    .order(ByteOrder.BIG_ENDIAN);
                int transactionId = in.getInt(12);
                byte[] wire;
                if (in.getInt(8) == 0) {
                    long id = connectCount.incrementAndGet() == 1
                        ? STALE_CONNECTION_ID : FRESH_CONNECTION_ID;
                    wire = connectReply(transactionId, id);
                } else if (in.getLong(0) == STALE_CONNECTION_ID) {
                    wire = errorReply(transactionId, "connection id stale, reconnect please");
                } else {
                    byte[] peer = {127, 0, 0, 1, 0x4E, 0x21}; // 127.0.0.1:20001
                    wire = ByteBuffer.allocate(20 + peer.length).order(ByteOrder.BIG_ENDIAN)
                        .putInt(1).putInt(transactionId)
                        .putInt(1800).putInt(1).putInt(3)
                        .put(peer).array();
                }
                server.send(new DatagramPacket(wire, wire.length,
                    packet.getAddress(), packet.getPort()));
            } catch (SocketTimeoutException ignored) {
                // 检查 running
            } catch (Exception ignored) {
                return;
            }
        }
    }

    /** connect 一律发 FRESH id；announce 一律回 error（对新旧 id 都拒绝）。 */
    private void serveAlwaysErrorAnnounce(DatagramSocket server) {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                server.setSoTimeout(200);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                server.receive(packet);
                ByteBuffer in = ByteBuffer.wrap(packet.getData(), 0, packet.getLength())
                    .order(ByteOrder.BIG_ENDIAN);
                byte[] wire;
                if (in.getInt(8) == 0) {
                    connectCount.incrementAndGet();
                    wire = connectReply(in.getInt(12), FRESH_CONNECTION_ID);
                } else {
                    wire = errorReply(in.getInt(12), "connection still stale");
                }
                server.send(new DatagramPacket(wire, wire.length,
                    packet.getAddress(), packet.getPort()));
            } catch (SocketTimeoutException ignored) {
                // 检查 running
            } catch (Exception ignored) {
                return;
            }
        }
    }

    /** connect 正常应答；announce 只登记不应答（模拟无响应 tracker，把在途扣住）。 */
    private void serveConnectOnly(DatagramSocket server, CountDownLatch announceHeld) {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                server.setSoTimeout(200);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                server.receive(packet);
                ByteBuffer in = ByteBuffer.wrap(packet.getData(), 0, packet.getLength())
                    .order(ByteOrder.BIG_ENDIAN);
                if (in.getInt(8) == 0) {
                    byte[] wire = connectReply(in.getInt(12), FRESH_CONNECTION_ID);
                    server.send(new DatagramPacket(wire, wire.length,
                        packet.getAddress(), packet.getPort()));
                } else {
                    announceHeld.countDown();
                }
            } catch (SocketTimeoutException ignored) {
                // 检查 running
            } catch (Exception ignored) {
                return;
            }
        }
    }

    private static byte[] connectReply(int transactionId, long connectionId) {
        return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(0).putInt(transactionId).putLong(connectionId).array();
    }

    private static byte[] errorReply(int transactionId, String message) {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + text.length).order(ByteOrder.BIG_ENDIAN)
            .putInt(3).putInt(transactionId).put(text).array();
    }
}
