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

            // 同一 client 二次 announce：命中缓存，不再 connect
            client.announce("udp://127.0.0.1:" + server.getLocalPort() + "/announce", request);
            assertEquals(1, connectCount.get());
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
