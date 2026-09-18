package io.github.oatelauser.thunder.dht.internal;

import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Builder;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.NodeId;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KrpcRpc 直测：脚本化对方节点（DatagramSocket 回 pong），聚焦 2 字节事务 ID
 * 并发碰撞时的事务表语义——先登记的事务不得被后来者顶掉。
 */
class KrpcRpcTest {

    /**
     * 碰撞回归：慢节点（400ms 才回 pong）的事务 A 先登记 tid；同 tid 的事务 B 随后
     * 发往快节点。修复前 B 的 put 覆盖 A 的 future——A 的应答到达时表里已是 B，
     * A 白等 2s 超时返回 null。修复后 B 换新 tid 发送，两者各自配对应答。
     */
    @Test
    void collidingTransactionIdsDoNotDisplaceInFlightTransaction() throws Exception {
        try (DatagramSocket slowServer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DatagramSocket fastServer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             KrpcRpc rpc = new KrpcRpc(0, (address, message) -> { })) {
            CountDownLatch firstQuerySeen = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> serve(slowServer, 400, firstQuerySeen));
            Thread.ofVirtual().start(() -> serve(fastServer, 0, null));

            byte[] tid = {9, 9};
            CompletableFuture<KrpcMessage.Parsed> first = CompletableFuture.supplyAsync(() ->
                rpc.roundTrip(new InetSocketAddress("127.0.0.1", slowServer.getLocalPort()), ping(tid)));
            // 慢节点收到请求即证明 tid 已登记（roundTrip 先登记后发送）
            assertTrue(firstQuerySeen.await(3, TimeUnit.SECONDS), "首事务应已发出并占用事务表");

            KrpcMessage.Parsed secondResult = rpc.roundTrip(
                new InetSocketAddress("127.0.0.1", fastServer.getLocalPort()), ping(tid));

            assertNotNull(secondResult, "碰撞方换新 tid 后应正常完成");
            assertNotNull(first.get(3, TimeUnit.SECONDS), "在途事务不应被同 tid 后写者顶掉");
        }
    }

    private static Builder ping(byte[] transactionId) {
        return Builder.query(transactionId, "ping").id(new NodeId(new byte[20]));
    }

    /** 脚本化节点：收到 query 后延迟 delayMillis 毫秒回 tid 回显的 pong。 */
    private static void serve(DatagramSocket server, long delayMillis, CountDownLatch querySeen) {
        byte[] buffer = new byte[4096];
        while (!server.isClosed()) {
            try {
                server.setSoTimeout(500);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                server.receive(packet);
                if (querySeen != null) {
                    querySeen.countDown();
                }
                KrpcMessage.Parsed query;
                try {
                    query = KrpcMessage.parse(Arrays.copyOf(packet.getData(), packet.getLength()));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                Thread.sleep(delayMillis);
                byte[] wire = Builder.response(query.transactionId())
                    .id(new NodeId(new byte[20])).encode();
                server.send(new DatagramPacket(wire, wire.length,
                    packet.getAddress(), packet.getPort()));
            } catch (SocketTimeoutException ignored) {
                // 循环检查 closed
            } catch (Exception e) {
                return;
            }
        }
    }
}
