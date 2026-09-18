package io.github.oatelauser.thunder.core.internal.tracker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BEP 15 对拍：脚本化 UDP tracker 应答 connect/announce，验证两段事务与紧凑 peer 解析。 */
class UdpTrackerClientTest {

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
}
