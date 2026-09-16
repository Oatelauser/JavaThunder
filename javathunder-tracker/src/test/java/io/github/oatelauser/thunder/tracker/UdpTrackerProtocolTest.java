package io.github.oatelauser.thunder.tracker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEP 15 服务端对拍：脚本化 UDP 客户端打 connect/announce（布局镜像 core 的
 * UdpTrackerClient），校验事务 ID 回带、BEP 15 应答头顺序（interval/leechers/
 * seeders）、compact peers、stopped 摘除、非法报文静默丢弃、白名单 error 包。
 */
class UdpTrackerProtocolTest {

    private final HttpClient http = HttpClient.newHttpClient();

    /** connect → announce 全链路：HTTP 预注册的 peer 出现在 compact 应答里，UDP peer 进 stats。 */
    @Test
    void connectThenAnnounceReturnsCompactPeers() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            int udpPort = tracker.enableUdp(0);
            assertEquals(tracker.port(), udpPort, "port=0 必须与 HTTP 同端口");
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", udpPort);

            byte[] infoHash = infoHash(1);
            httpAnnounce(tracker, infoHash, 15500, 100_000, "started"); // 预置 leecher

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(2_000);
                long connectionId = connect(socket, target);
                ByteBuffer response = announce(socket, target, connectionId, infoHash,
                        15600, 0, 2 /* started */, 10);

                assertEquals(1, response.getInt(0), "action=announce");
                assertEquals(TID, response.getInt(4), "transaction id 必须回带");
                assertEquals(2, response.getInt(8), "interval 来自 announceIntervalSeconds");
                assertEquals(1, response.getInt(12), "leechers 在 seeders 之前（BEP 15 顺序）");
                assertEquals(1, response.getInt(16), "seeders：UDP 自身 left=0");
                response.position(20);
                byte[] peers = new byte[response.remaining()];
                response.get(peers);
                assertEquals(6, peers.length, "只有 HTTP 预注册的 peer（自身被排除）");
                assertArrayEquals(new byte[]{127, 0, 0, 1, (byte) (15500 >> 8), (byte) 15500}, peers);
            }

            assertEquals(2, tracker.stats().get(hex(1)).total());
            assertEquals(1, tracker.stats().get(hex(1)).seeders(), "UDP announce 的 left=0 → seeder");
        }
    }

    /** 非法报文静默丢弃（BEP 15 防放大）：未知 action、截断包、错误 protocol_id 的 connect。 */
    @Test
    void malformedPacketsAreSilentlyDropped() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", tracker.enableUdp(0));
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(500);

                // 未知 action=7
                ByteBuffer unknownAction = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                        .putLong(0L).putInt(7).putInt(99);
                assertTimeout(socket, target, unknownAction.array(), "unknown action");

                // 头部截断（8 字节）
                assertTimeout(socket, target, new byte[8], "truncated header");

                // connect 但 protocol_id 错误
                ByteBuffer badConnect = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                        .putLong(0xDEADBEEFL).putInt(0).putInt(98);
                assertTimeout(socket, target, badConnect.array(), "wrong connect protocol id");

                // 布局不完整的 announce（connect 之后只补 10 字节）
                long connectionId = connect(socket, target);
                ByteBuffer shortAnnounce = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN)
                        .putLong(connectionId).putInt(1).putInt(97);
                shortAnnounce.put(new byte[26]); // 总长 48 < 92
                assertTimeout(socket, target, shortAnnounce.array(), "truncated announce");
            }
        }
    }

    /** UDP event=stopped（3）：立即摘除，应答 peers 为空但计数/事务头仍规范。 */
    @Test
    void stoppedEventRemovesUdpPeer() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", tracker.enableUdp(0));
            byte[] infoHash = infoHash(2);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(2_000);
                long connectionId = connect(socket, target);
                announce(socket, target, connectionId, infoHash, 15700, 500, 2, 10);
                assertEquals(1, tracker.stats().get(hex(2)).total());

                ByteBuffer stopped = announce(socket, target, connectionId, infoHash,
                        15700, 0, 3 /* stopped */, 10);
                assertEquals(1, stopped.getInt(0));
                assertEquals(TID, stopped.getInt(4));
                assertEquals(20, stopped.limit(), "stopped 应答仅 20 字节头，无 peers");
                assertNull(tracker.stats().get(hex(2)));
            }
        }
    }

    /** 白名单拒绝走 UDP error 包（action=3）：事务 ID 回带 + "torrent not registered"。 */
    @Test
    void nonWhitelistedTorrentGetsErrorPacket() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", tracker.enableUdp(0));
            tracker.enableWhitelist(Arrays.asList(infoHash(3)));
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(2_000);
                long connectionId = connect(socket, target);

                ByteBuffer denied = announce(socket, target, connectionId, infoHash(4),
                        15800, 0, 2, 10);
                assertEquals(3, denied.getInt(0), "action=error");
                assertEquals(TID, denied.getInt(4));
                byte[] message = new byte[denied.remaining() - 8];
                denied.position(8).get(message);
                assertEquals("torrent not registered", new String(message, java.nio.charset.StandardCharsets.UTF_8));
                assertNull(tracker.stats().get(hex(4)), "被拒 swarm 不得残留");

                announce(socket, target, connectionId, infoHash(3), 15801, 0, 2, 10);
                assertEquals(1, tracker.stats().get(hex(3)).total(), "白名单内正常放行");
            }
        }
    }

    // ---- 脚本化 UDP 客户端（布局镜像 core UdpTrackerClient：port 为 int、numwant 在其后） ----

    private static final int TID = 0x5A5A5A5A;

    private long connect(DatagramSocket socket, InetSocketAddress target) throws IOException {
        ByteBuffer out = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putLong(0x41727101980AL)
                .putInt(0)
                .putInt(TID);
        ByteBuffer response = exchange(socket, target, out.array());
        assertEquals(0, response.getInt(0), "action=connect");
        assertEquals(TID, response.getInt(4));
        assertEquals(16, response.remaining(), "connect 应答固定 16 字节");
        return response.getLong(8);
    }

    private ByteBuffer announce(DatagramSocket socket, InetSocketAddress target, long connectionId,
                                byte[] infoHash, int port, long left, int event, int numwant)
            throws IOException {
        ByteBuffer out = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN)
                .putLong(connectionId)
                .putInt(1)
                .putInt(TID)
                .put(infoHash)
                .put(new byte[20]) // peer_id
                .putLong(0L)       // downloaded
                .putLong(0L)       // uploaded
                .putLong(left)
                .putInt(event)
                .putInt(port)
                .putInt(numwant);
        return exchange(socket, target, out.array());
    }

    private static ByteBuffer exchange(DatagramSocket socket, InetSocketAddress target, byte[] wire)
            throws IOException {
        socket.send(new DatagramPacket(wire, wire.length, target.getAddress(), target.getPort()));
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return ByteBuffer.wrap(Arrays.copyOfRange(packet.getData(), 0, packet.getLength()))
                .order(ByteOrder.BIG_ENDIAN);
    }

    /** 期待无应答（防放大语义）：收到包即失败。 */
    private static void assertTimeout(DatagramSocket socket, InetSocketAddress target,
                                      byte[] wire, String context) throws IOException {
        socket.send(new DatagramPacket(wire, wire.length, target.getAddress(), target.getPort()));
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        assertThrows(SocketTimeoutException.class, () -> socket.receive(packet), context);
    }

    private void httpAnnounce(EmbeddedTracker tracker, byte[] infoHash, int port, long left, String event)
            throws Exception {
        StringBuilder url = new StringBuilder(tracker.announceUrl());
        url.append("?info_hash=").append(percentEncode(infoHash));
        url.append("&peer_id=").append(percentEncode(new byte[20]));
        url.append("&port=").append(port).append("&left=").append(left).append("&event=").append(event);
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(url.toString())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().length > 0);
    }

    private static byte[] infoHash(int seed) {
        byte[] hash = new byte[20];
        new Random(seed).nextBytes(hash);
        return hash;
    }

    private static String hex(int seed) {
        return java.util.HexFormat.of().formatHex(infoHash(seed));
    }

    private static String percentEncode(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 3);
        for (byte b : raw) {
            out.append('%').append(String.format("%02X", b));
        }
        return out.toString();
    }
}
