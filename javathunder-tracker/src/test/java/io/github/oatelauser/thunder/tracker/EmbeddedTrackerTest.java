package io.github.oatelauser.thunder.tracker;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产化能力单测：固定端口起停 / announce 注册与 stats / 过期摘除（手动 sweep +
 * 后台线程）/ stopped 摘除 / 直接注册不过期 / 多 swarm 并发。
 * 过期用小 interval（1s → 过期 2s）加速。
 */
class EmbeddedTrackerTest {

    private final HttpClient http = HttpClient.newHttpClient();

    /** 固定端口：close 后同端口可重启（0=随机保持 start() 兼容）。 */
    @Test
    void fixedPortSurvivesRestart() throws Exception {
        int port;
        try (EmbeddedTracker tracker = EmbeddedTracker.start(0)) {
            port = tracker.port();
            assertTrue(port > 0);
            assertEquals(port, parsePort(tracker.announceUrl()));
            announce(tracker, infoHash(1), 15000, 100, null);
            assertEquals(1, tracker.stats().get(hex(1)).total());
        }
        try (EmbeddedTracker restarted = EmbeddedTracker.start(port)) {
            assertEquals(port, restarted.port());
            // 重启后状态清空，可继续服务
            BDict response = announce(restarted, infoHash(1), 15001, 0, null);
            assertEquals(2, intOf(response, "interval"));
            assertEquals(1, restarted.stats().get(hex(1)).seeders());
        }
    }

    /** announce 注册 Peer：stats 分类正确（left=0 做种），响应排除自己。 */
    @Test
    void announceRegistersPeerAndStatsReflectRole() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            byte[] infoHash = infoHash(2);
            announce(tracker, infoHash, 15100, 0, "started");    // seeder
            announce(tracker, infoHash, 15101, 999_999, "started"); // leecher

            EmbeddedTracker.SwarmStats stats = tracker.stats().get(hex(2));
            assertEquals(1, stats.seeders());
            assertEquals(1, stats.leechers());
            assertEquals(2, stats.total());

            // 做种方视角：peers 只含对方（127.0.0.1:15101），不含自己
            BDict response = announce(tracker, infoHash, 15100, 0, null);
            assertEquals(2, intOf(response, "interval"));
            assertEquals(2, intOf(response, "complete") + intOf(response, "incomplete"));
            byte[] peers = ((BString) response.get("peers")).value();
            assertEquals(6, peers.length);
            assertEquals("127.0.0.1", (peers[0] & 0xFF) + "." + (peers[1] & 0xFF) + "."
                    + (peers[2] & 0xFF) + "." + (peers[3] & 0xFF));
            assertEquals(15101, ((peers[4] & 0xFF) << 8) | (peers[5] & 0xFF));
        }
    }

    /** 过期摘除：interval=1s → 过期 2s。老 Peer 被手动 sweep 摘除，续期 Peer 存活，
     * 停止续期后由后台线程（500ms 周期）摘除。 */
    @Test
    void expiredPeersAreEvictedManuallyAndByBackgroundSweeper() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start(0, 1)) {
            byte[] infoHash = infoHash(3);
            announce(tracker, infoHash, 15200, 100, "started"); // A：一次性
            announce(tracker, infoHash, 15201, 100, "started"); // B：将续期一次

            Thread.sleep(1_500);
            announce(tracker, infoHash, 15201, 100, null);      // B 续期（lastSeen≈1.5s）

            Thread.sleep(900); // A 年龄 ≈2.4s > 2s；B 年龄 ≈0.9s < 2s
            tracker.sweepExpiredPeers(); // 确定性兜底（后台线程可能已先行摘除 A）
            assertEquals(1, tracker.stats().get(hex(3)).total(), "stale peer gone, refreshed peer must survive");

            // B 停止续期：后台 sweeper（无需手动调用）应在过期后摘除
            Thread.sleep(3_000);
            assertNull(tracker.stats().get(hex(3)), "expired peer must be evicted by background sweeper");
        }
    }

    /** event=stopped 立即摘除；stopped 响应 peers 为空。 */
    @Test
    void stoppedEventRemovesPeerImmediately() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            byte[] infoHash = infoHash(4);
            announce(tracker, infoHash, 15300, 100, "started");
            announce(tracker, infoHash, 15301, 100, "started");
            assertEquals(2, tracker.stats().get(hex(4)).total());

            BDict stopped = announce(tracker, infoHash, 15300, 100, "stopped");
            assertEquals(0, ((BString) stopped.get("peers")).value().length);
            assertEquals(1, tracker.stats().get(hex(4)).total());
        }
    }

    /** 直接注册（FakeSeeder 场景）无 announce 生命周期：不过期，验收测试节奏不受误摘。 */
    @Test
    void directlyRegisteredSeederSurvivesExpiry() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start(0, 1)) { // 过期 2s
            byte[] infoHash = infoHash(5);
            tracker.register(infoHash, 15400);
            Thread.sleep(2_600); // 超过过期阈值
            tracker.sweepExpiredPeers();
            EmbeddedTracker.SwarmStats stats = tracker.stats().get(hex(5));
            assertNotNull(stats);
            assertEquals(1, stats.seeders());
        }
    }

    /** 多 swarm 互相隔离：peers 与 stats 不串台。 */
    @Test
    void multipleSwarmsAreIndependent() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            announce(tracker, infoHash(6), 15500, 0, "started");
            announce(tracker, infoHash(7), 15501, 0, "started");

            assertEquals(2, tracker.stats().size());
            BDict response = announce(tracker, infoHash(6), 15500, 0, null);
            assertEquals(0, ((BString) response.get("peers")).value().length,
                    "swarm 6 must not see swarm 7 peers");
            assertEquals(1, tracker.stats().get(hex(6)).total());
            assertEquals(1, tracker.stats().get(hex(7)).total());
        }
    }

    /** 并发加固：多 swarm × 多 Peer 并发 announce/stopped/续期，最终 stats 精确。 */
    @Test
    void concurrentAnnouncesAcrossSwarmsStayConsistent() throws Exception {
        int swarmCount = 4;
        int peersPerSwarm = 25;
        try (EmbeddedTracker tracker = EmbeddedTracker.start(0, 1800)) {
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch done = new CountDownLatch(swarmCount * peersPerSwarm);
            AtomicInteger failures = new AtomicInteger();
            for (int s = 0; s < swarmCount; s++) {
                final byte[] infoHash = infoHash(10 + s);
                for (int p = 0; p < peersPerSwarm; p++) {
                    final int port = 16000 + s * 1000 + p;
                    final long left = p % 2 == 0 ? 0 : 12345;
                    pool.submit(() -> {
                        try {
                            announce(tracker, infoHash, port, left, "started");
                            announce(tracker, infoHash, port, left, null); // 续期
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
                }
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdownNow();
            assertEquals(0, failures.get());

            Map<String, EmbeddedTracker.SwarmStats> stats = tracker.stats();
            assertEquals(swarmCount, stats.size());
            for (int s = 0; s < swarmCount; s++) {
                EmbeddedTracker.SwarmStats swarm = stats.get(hex(10 + s));
                assertEquals(peersPerSwarm, swarm.total());
                // 偶数序号（含 0）left=0 做种：0..24 共 13 个
                assertEquals((peersPerSwarm + 1) / 2, swarm.seeders());
                assertEquals(peersPerSwarm / 2, swarm.leechers());
            }
        }
    }

    // ---- helpers ----

    private static byte[] infoHash(int seed) {
        byte[] hash = new byte[20];
        new Random(seed).nextBytes(hash);
        return hash;
    }

    private static String hex(int seed) {
        return HexFormat.of().formatHex(infoHash(seed));
    }

    private static int parsePort(String announceUrl) {
        return Integer.parseInt(announceUrl.replaceAll(".*:(\\d+)/announce", "$1"));
    }

    /** 发一次 announce，返回解码后的响应字典。 */
    private BDict announce(EmbeddedTracker tracker, byte[] infoHash, int port, long left, String event)
            throws Exception {
        StringBuilder url = new StringBuilder(tracker.announceUrl());
        url.append("?info_hash=").append(percentEncode(infoHash));
        url.append("&peer_id=").append(percentEncode(("-javathunder-test-" + port + "xxxx")
                .getBytes(StandardCharsets.US_ASCII)));
        url.append("&port=").append(port);
        url.append("&left=").append(left);
        url.append("&compact=1&no_peer_id=1&numwant=50");
        if (event != null) {
            url.append("&event=").append(event);
        }
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(url.toString())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        BencodeValue decoded = Bencode.decode(response.body());
        assertTrue(decoded instanceof BDict, "response must be a bencode dict");
        return (BDict) decoded;
    }

    private static String percentEncode(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 3);
        for (byte b : raw) {
            out.append('%').append(String.format("%02X", b));
        }
        return out.toString();
    }

    private static int intOf(BDict dict, String key) {
        BencodeValue value = dict.get(key);
        return value instanceof BInteger i ? (int) i.value() : -1;
    }
}
