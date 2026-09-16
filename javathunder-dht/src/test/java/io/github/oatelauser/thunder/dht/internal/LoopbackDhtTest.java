package io.github.oatelauser.thunder.dht.internal;

import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Builder;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.NodeId;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回环 DHT 对拍：脚本化"对方节点"（DatagramSocket 应答 ping/find_node/get_peers/
 * announce_peer），验证 DhtClient 的自举、迭代查找与 get_peers 链路。
 */
class LoopbackDhtTest {

    @Test
    void bootstrapAndIterativeLookup() throws Exception {
        NodeId serverId = DhtClient.randomId();
        try (DatagramSocket server = new DatagramSocket(
            new InetSocketAddress("127.0.0.1", 0));
             DhtClient client = new DhtClient(0)) {

            int serverPort = server.getLocalPort();
            Thread.ofVirtual().start(() -> serve(server, serverId));

            // 自举：从脚本节点拿到"更近节点"，再迭代
            client.bootstrap(List.of("127.0.0.1:" + serverPort));
            assertTrue(client.knownNodes() > 0, "routing table should learn nodes");
        }
    }

    @Test
    void getPeersReturnsPeerFromScriptedNode() throws Exception {
        NodeId serverId = DhtClient.randomId();
        try (DatagramSocket server = new DatagramSocket(
            new InetSocketAddress("127.0.0.1", 0));
             DhtClient client = new DhtClient(0)) {

            int serverPort = server.getLocalPort();
            Thread.ofVirtual().start(() -> serve(server, serverId));

            client.bootstrap(List.of("127.0.0.1:" + serverPort));
            byte[] infoHash = DhtClient.randomId().bytes();
            List<InetSocketAddress> peers = client.getPeers(infoHash).get(15, TimeUnit.SECONDS);

            assertEquals(1, peers.size());
            assertEquals("127.0.0.1", peers.get(0).getHostString());
            assertEquals(0xC935, peers.get(0).getPort()); // 201*256+53 = 51509
        }
    }

    /** 脚本化对方节点：ping→pong；find_node/get_peers→一个更近节点 + 一个 peer + token。 */
    private static void serve(DatagramSocket server, NodeId serverId) {
        byte[] buffer = new byte[4096];
        // 固定"更近节点"（返回给客户端做迭代）：地址指向我们自己（回环即可迭代到终点）
        while (!server.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                server.setSoTimeout(500);
                server.receive(packet);
                KrpcMessage.Parsed query;
                try {
                    query = KrpcMessage.parse(Arrays.copyOf(packet.getData(), packet.getLength()));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (!"q".equals(query.type())) {
                    continue;
                }
                Builder response = Builder.response(query.transactionId()).id(serverId);
                switch (query.method() == null ? "" : query.method()) {
                    case "ping" -> {
                    }
                    case "find_node", "get_peers" ->
                        serveLookup(query, response, server, serverId);
                    case "announce_peer" -> {
                        // 已宣告（断言由 get_peers 流程隐含——token 校验通过才会被接受）
                    }
                    default -> {
                        continue;
                    }
                }
                byte[] wire = response.encode();
                server.send(new DatagramPacket(wire, wire.length,
                    packet.getAddress(), packet.getPort()));
            } catch (SocketTimeoutException ignored) {
                // 循环检查 closed
            } catch (Exception e) {
                return;
            }
        }
    }

    /** find_node/get_peers 共用应答：紧凑节点自我指回以收敛迭代，get_peers 再附 token 与 peer。 */
    private static void serveLookup(KrpcMessage.Parsed query, Builder response,
                                    DatagramSocket server, NodeId serverId) {
        // 紧凑节点：serverId + 127.0.0.1 + serverPort（自我指回，迭代收敛）
        byte[] compact = new byte[26];
        System.arraycopy(serverId.bytes(), 0, compact, 0, 20);
        compact[20] = 127;
        compact[21] = 0;
        compact[22] = 0;
        compact[23] = 1;
        compact[24] = (byte) (server.getLocalPort() >> 8);
        compact[25] = (byte) (server.getLocalPort() & 0xFF);
        response.result("nodes", new BString(compact));
        if ("get_peers".equals(query.method())) {
            response.result("token", new BString(new byte[]{1, 2, 3}));
            response.result("values", new BList(List.of(
                new BString(new byte[]{127, 0, 0, 1, (byte) 0xC9, 0x35}))));
        }
    }

}
