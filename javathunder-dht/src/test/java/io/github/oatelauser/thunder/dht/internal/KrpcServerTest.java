package io.github.oatelauser.thunder.dht.internal;

import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Builder;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.NodeId;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Parsed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KRPC 服务侧单测（BEP 5 应答面）：脚本化客户端直发查询，验证 ping/find_node/
 * get_peers/announce_peer 的应答、token 轮换与错误码。时钟注入驱动 token 时段。
 */
class KrpcServerTest {

    private KrpcRpc rpc;
    private final AtomicLong clock = new AtomicLong(KrpcServer.TOKEN_ROTATE_MILLIS);
    private final RoutingTable table = new RoutingTable(DhtClient.randomId());
    private final NodeId selfId = DhtClient.randomId();
    private DatagramSocket client;

    @AfterEach
    void stop() throws IOException {
        if (rpc != null) {
            rpc.close();
        }
        if (client != null) {
            client.close();
        }
    }

    /** 起服务与客户端套接字；返回服务端口。 */
    private int startServer() throws IOException {
        rpc = new KrpcRpc(0, (source, message) -> {
        });
        new KrpcServer(selfId, table, rpc, clock::get);
        client = new DatagramSocket();
        return rpc.port();
    }

    private Parsed exchange(int serverPort, Builder query) throws IOException {
        byte[] wire = query.encode();
        client.send(new DatagramPacket(wire, wire.length,
                InetAddress.getLoopbackAddress(), serverPort));
        byte[] buffer = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            client.setSoTimeout(3000);
            client.receive(packet);
        } catch (SocketTimeoutException e) {
            return null;
        }
        return KrpcMessage.parse(Arrays.copyOf(buffer, packet.getLength()));
    }

    @Test
    void pingRespondsWithSelfId() throws IOException {
        int port = startServer();
        Parsed reply = exchange(port, Builder.query(rpc.newTransactionId(), "ping").id(selfId));
        assertNotNull(reply);
        assertEquals("r", reply.type());
        assertArrayEquals(selfId.bytes(), reply.nodeId().bytes());
    }

    @Test
    void findNodeReturnsSeededClosestNodes() throws IOException {
        NodeId seeded = DhtClient.randomId();
        table.offer(seeded, "127.0.0.1", 16881);
        int port = startServer();
        // 查询方自身也会被登记进表：取 selfId 的最远 id 作 sender，保证种子是最近节点
        byte[] far = selfId.bytes().clone();
        for (int i = 0; i < far.length; i++) {
            far[i] ^= (byte) 0xFF;
        }
        Parsed reply = exchange(port, Builder.query(rpc.newTransactionId(), "find_node")
                .arg("target", new BString(selfId.bytes())).id(new NodeId(far)));
        assertNotNull(reply);
        boolean found = reply.nodes().stream().anyMatch(node -> node.nodeId() != null
                && Arrays.equals(node.nodeId(), seeded.bytes()));
        assertTrue(found, "nodes 应包含种子节点（实际 " + reply.nodes().size() + " 个）");
    }

    @Test
    void getPeersIssuesTokenThenAnnounceThenValuesFlow() throws IOException {
        int port = startServer();
        byte[] infoHash = new byte[20];
        Arrays.fill(infoHash, (byte) 7);

        Parsed first = exchange(port, Builder.query(rpc.newTransactionId(), "get_peers")
                .arg("info_hash", new BString(infoHash)).id(selfId));
        assertNotNull(first);
        assertNotNull(first.token(), "get_peers 必发 token");
        assertTrue(first.values().isEmpty(), "尚无 announce，values 为空");

        Parsed announced = exchange(port, Builder.query(rpc.newTransactionId(), "announce_peer")
                .arg("info_hash", new BString(infoHash))
                .arg("port", new BInteger(12345))
                .arg("token", new BString(first.token()))
                .id(selfId));
        assertNotNull(announced);
        assertEquals("r", announced.type(), "合法 token 的 announce 应成功");

        Parsed second = exchange(port, Builder.query(rpc.newTransactionId(), "get_peers")
                .arg("info_hash", new BString(infoHash)).id(selfId));
        assertNotNull(second);
        assertEquals(1, second.values().size());
        assertEquals(12345, second.values().get(0).port());
        assertEquals("127.0.0.1", second.values().get(0).host());
    }

    @Test
    void announceWithForgedTokenIsRejected() throws IOException {
        int port = startServer();
        byte[] infoHash = new byte[20];
        byte[] forged = new byte[8];
        Arrays.fill(forged, (byte) 9);
        Parsed reply = exchange(port, Builder.query(rpc.newTransactionId(), "announce_peer")
                .arg("info_hash", new BString(infoHash))
                .arg("port", new BInteger(1))
                .arg("token", new BString(forged))
                .id(selfId));
        assertNotNull(reply);
        assertEquals("e", reply.type());
        assertEquals(203, reply.error().get(0), "伪造 token → Protocol Error");
    }

    @Test
    void tokenOlderThanPreviousBucketExpires() throws IOException {
        clock.set(0); // bucket 0
        int port = startServer();
        byte[] infoHash = new byte[20];
        Parsed first = exchange(port, Builder.query(rpc.newTransactionId(), "get_peers")
                .arg("info_hash", new BString(infoHash)).id(selfId));
        assertNotNull(first);
        clock.set(2 * KrpcServer.TOKEN_ROTATE_MILLIS + 1); // bucket 2：只接受 2/1
        Parsed reply = exchange(port, Builder.query(rpc.newTransactionId(), "announce_peer")
                .arg("info_hash", new BString(infoHash))
                .arg("port", new BInteger(1))
                .arg("token", new BString(first.token()))
                .id(selfId));
        assertNotNull(reply);
        assertEquals("e", reply.type(), "两个时段前的 token 必须过期");
    }

    @Test
    void unknownMethodGets204() throws IOException {
        int port = startServer();
        Parsed reply = exchange(port, Builder.query(rpc.newTransactionId(), "vote_for_me")
                .id(selfId));
        assertNotNull(reply);
        assertEquals("e", reply.type());
        assertEquals(204, reply.error().get(0), "未知方法 → Method Unknown");
    }

    @Test
    void impliedPortOverridesAnnouncedPort() throws IOException {
        int port = startServer();
        byte[] infoHash = new byte[20];
        Arrays.fill(infoHash, (byte) 3);
        Parsed tokenHolder = exchange(port, Builder.query(rpc.newTransactionId(), "get_peers")
                .arg("info_hash", new BString(infoHash)).id(selfId));
        assertNotNull(tokenHolder);
        // 自报端口 1 是诱饵；implied_port=1 → 采用 UDP 源端口（NAT 场景语义）
        Parsed announced = exchange(port, Builder.query(rpc.newTransactionId(), "announce_peer")
                .arg("info_hash", new BString(infoHash))
                .arg("port", new BInteger(1))
                .arg("implied_port", new BInteger(1))
                .arg("token", new BString(tokenHolder.token()))
                .id(selfId));
        assertEquals("r", announced.type());
        Parsed after = exchange(port, Builder.query(rpc.newTransactionId(), "get_peers")
                .arg("info_hash", new BString(infoHash)).id(selfId));
        assertEquals(client.getLocalPort(), after.values().get(0).port(),
                "implied_port=1 应登记 UDP 源端口而非自报端口");
    }

    @Test
    void dhtClientWiringRespondsToPing() throws IOException {
        try (DhtClient dht = new DhtClient(0)) {
            DatagramSocket socket = new DatagramSocket();
            try {
                byte[] wire = Builder.query(new byte[]{1, 1}, "ping")
                        .id(DhtClient.randomId()).encode();
                socket.send(new DatagramPacket(wire, wire.length,
                        InetAddress.getLoopbackAddress(), dht.port()));
                byte[] buffer = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.setSoTimeout(3000);
                socket.receive(packet);
                Parsed reply = KrpcMessage.parse(Arrays.copyOf(buffer, packet.getLength()));
                assertEquals("r", reply.type(), "全功能 DhtClient 应答 ping");
                assertNull(reply.error());
            } finally {
                socket.close();
            }
        }
    }
}
