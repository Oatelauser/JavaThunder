package io.github.oatelauser.thunder.tracker;

import io.github.oatelauser.thunder.core.internal.tracker.AnnounceRequest;
import io.github.oatelauser.thunder.core.internal.tracker.AnnounceResponse;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerEvent;
import io.github.oatelauser.thunder.core.internal.tracker.UdpTrackerClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真互通端到端：core 的 {@link UdpTrackerClient}（connect 60s 缓存 + 指数退避）
 * 对本地 {@link TrackerServer}（UDP 开启）announce，拿到直接注册的 peer——
 * 服务端报文布局与客户端解析的每一处偏移都被真实代码对拍覆盖。
 */
class UdpClientInteropTest {

    @Test
    void coreUdpClientReceivesRegisteredPeerFromLocalServer() throws Exception {
        byte[] infoHash = new byte[20];
        new Random(7).nextBytes(infoHash);
        try (TrackerServer server = TrackerServer.start(0, 7)) {
            int udpPort = server.enableUdp(0);
            assertEquals(server.port(), udpPort);
            server.register(infoHash, 15123); // 直接注册的做种方（FakeSeeder 形态）

            try (UdpTrackerClient client = new UdpTrackerClient()) {
                AnnounceRequest request = new AnnounceRequest(
                        infoHash,
                        "-JT0001-interop00001".getBytes(StandardCharsets.US_ASCII),
                        6881, 0, 0, 999_999, TrackerEvent.STARTED, 10);
                AnnounceResponse response = client.announce(
                        "udp://127.0.0.1:" + udpPort + "/announce", request);

                assertEquals(7, response.interval(), "服务端配置的间隔必须透传");
                assertEquals(1, response.seeders(), "直接注册的做种方计入 complete");
                assertEquals(1, response.leechers(), "客户端自身以 left>0 计入 incomplete（计数含自身，BEP 15 常规）");
                assertEquals(1, response.peers().size());
                InetSocketAddress peer = response.peers().get(0);
                assertEquals("127.0.0.1", peer.getHostString());
                assertEquals(15123, peer.getPort());
                assertNotNull(server.stats().values().iterator().next());
                assertEquals(1, server.stats().values().iterator().next().leechers(),
                        "客户端自身以 left>0 注册为 leecher");

                // 二次 announce 复用缓存的 connection_id，仍能拿到 peer
                AnnounceResponse again = client.announce(
                        "udp://127.0.0.1:" + udpPort + "/announce", request);
                assertTrue(again.peers().contains(peer));
            }
        }
    }
}
