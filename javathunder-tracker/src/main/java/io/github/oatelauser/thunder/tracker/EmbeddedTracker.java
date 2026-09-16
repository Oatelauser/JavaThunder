package io.github.oatelauser.thunder.tracker;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;

/**
 * 内嵌 Tracker（组合根与生命周期）：装配 HTTP 端点（announce/scrape/stats/metrics）、
 * 可选 UDP（BEP 15）与过期清理线程，公开测试友好的工厂与查询 API。
 *
 * <p>职责拆分：领域状态在 {@link SwarmRegistry}，HTTP announce/scrape 在
 * {@link AnnounceHttpHandler}/{@link ScrapeHttpHandler}，页面渲染在
 * {@link StatsPage}/{@link MetricsPage}，UDP 协议在 {@link UdpTrackerServer}，
 * 计数在 {@link TrackerMetrics}。
 *
 * <p>testkit 兼容默认：{@link #start()} 绑定回环、随机端口、interval=2s、
 * 无 UDP 无白名单。生产形态（0.0.0.0、默认 6881 / 1800s）用
 * {@link #start(InetAddress, int, int)} 绑通配地址；可执行 jar 入口见 {@link TrackerMain}。
 */
public final class EmbeddedTracker implements AutoCloseable {

    /**
     * 每个 swarm 的可观测快照：做种 / 下载 / 总数。
     */
    public record SwarmStats(int seeders, int leechers, int total) {
    }

    private static final long MAX_SWEEP_PERIOD_MILLIS = 30_000;
    private static final long MIN_SWEEP_PERIOD_MILLIS = 250;

    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final int announceIntervalSeconds;
    private final SwarmRegistry registry;
    private final TrackerMetrics metrics = new TrackerMetrics();
    private final Thread expirySweeper;
    private volatile @Nullable UdpTrackerServer udp;
    private volatile boolean closed;

    private EmbeddedTracker(HttpServer server, ExecutorService httpExecutor, int announceIntervalSeconds) {
        this.server = server;
        this.httpExecutor = httpExecutor;
        this.announceIntervalSeconds = announceIntervalSeconds;
        this.registry = new SwarmRegistry(announceIntervalSeconds);
        long sweepPeriodMillis = Math.clamp(
                announceIntervalSeconds * 1000L / 2, MIN_SWEEP_PERIOD_MILLIS, MAX_SWEEP_PERIOD_MILLIS);
        this.expirySweeper = Thread.ofVirtual().name("tracker-peer-expiry").unstarted(() -> {
            while (!closed) {
                try {
                    Thread.sleep(sweepPeriodMillis);
                } catch (InterruptedException e) {
                    return; // closed
                }
                try {
                    registry.sweepExpiredPeers();
                } catch (RuntimeException ignored) {
                    // 单轮失败不终止清理线程
                }
            }
        });
    }

    // ---- 工厂 ----

    /**
     * testkit 兼容：回环 + 随机端口 + interval=2s。
     */
    public static EmbeddedTracker start() throws IOException {
        return start(InetAddress.getLoopbackAddress(), 0, 2);
    }

    /**
     * 固定端口（0 = 随机），回环 + interval=2s。
     */
    public static EmbeddedTracker start(int port) throws IOException {
        return start(port, 2);
    }

    /**
     * 固定端口（0 = 随机）+ 自定间隔（秒）：小间隔可加速过期类测试。回环绑定。
     */
    public static EmbeddedTracker start(int port, int announceIntervalSeconds) throws IOException {
        return start(InetAddress.getLoopbackAddress(), port, announceIntervalSeconds);
    }

    /**
     * 完全控制绑定地址 / 端口 / announce 间隔（生产入口：通配地址 + 6881 + 1800s）。
     */
    public static EmbeddedTracker start(InetAddress bindAddress, int port, int announceIntervalSeconds)
            throws IOException {
        if (announceIntervalSeconds <= 0) {
            throw new IllegalArgumentException("announceIntervalSeconds must be > 0");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 64);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        EmbeddedTracker tracker = new EmbeddedTracker(server, executor, announceIntervalSeconds);
        AnnounceHttpHandler announce = new AnnounceHttpHandler(
                tracker.registry, tracker.metrics, announceIntervalSeconds);
        ScrapeHttpHandler scrape = new ScrapeHttpHandler(tracker.registry, tracker.metrics);
        server.createContext("/announce", announce::handle);
        server.createContext("/scrape", scrape::handle);
        server.createContext("/stats", exchange -> Http.respondText(exchange,
                StatsPage.render(tracker.registry, tracker.metrics, tracker.udpPort(),
                        tracker.registry.whitelistEnabled()),
                "text/html; charset=utf-8"));
        server.createContext("/metrics", exchange -> Http.respondText(exchange,
                MetricsPage.render(tracker.registry, tracker.metrics),
                "text/plain; version=0.0.4; charset=utf-8"));
        server.start();
        tracker.expirySweeper.start();
        return tracker;
    }

    // ---- 查询 API ----

    /**
     * 实际监听端口（固定端口绑定时即传入值）。
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * 回环形态的 announce URL（无论绑定地址为何，回环总是可达）。
     */
    public String announceUrl() {
        return "http://127.0.0.1:" + port() + "/announce";
    }

    /**
     * UDP 监听端口；未开启返回 -1。
     */
    public int udpPort() {
        UdpTrackerServer current = udp;
        return current == null ? -1 : current.port();
    }

    /**
     * 回环形态的 UDP announce URL；未开启返回 null。
     */
    public @Nullable String udpAnnounceUrl() {
        int port = udpPort();
        return port < 0 ? null : "udp://127.0.0.1:" + port + "/announce";
    }

    /**
     * 开启 UDP announce（默认关闭）：port &gt; 0 指定监听端口，port = 0 与 HTTP
     * 同端口；绑定地址与 HTTP 一致。返回实际监听端口。每实例只能开启一次。
     */
    public synchronized int enableUdp(int port) throws IOException {
        if (closed) {
            throw new IllegalStateException("tracker is closed");
        }
        if (udp != null) {
            throw new IllegalStateException("udp already enabled on port " + udp.port());
        }
        int bindPort = port > 0 ? port : server.getAddress().getPort();
        DatagramSocket socket = new DatagramSocket(
                new InetSocketAddress(server.getAddress().getAddress(), bindPort));
        UdpTrackerServer udpServer = new UdpTrackerServer(
                socket, registry, metrics, announceIntervalSeconds);
        udpServer.serveAsync();
        udp = udpServer;
        return bindPort;
    }

    /**
     * 种子方直接注册（FakeSeeder 用，绕过 announce）：无 announce 生命周期，不过期。
     */
    public void register(byte[] infoHash, int port) {
        registry.register(infoHash, port);
    }

    /**
     * 每个 info-hash（hex）的 seeders/leechers/总数快照；空 swarm 不出现。
     */
    public Map<String, SwarmStats> stats() {
        return registry.stats();
    }

    // ---- 白名单 ----

    /**
     * 启用白名单：仅列出的 info-hash 可 announce（scrape/统计不受限）；重复调用替换旧表。
     */
    public void enableWhitelist(Collection<byte[]> infoHashes) {
        registry.enableWhitelist(infoHashes);
    }

    /**
     * 关闭白名单（默认：全放行）。
     */
    public void disableWhitelist() {
        registry.disableWhitelist();
    }

    /**
     * 白名单是否启用。
     */
    public boolean whitelistEnabled() {
        return registry.whitelistEnabled();
    }

    // ---- 测试钩子与生命周期 ----

    /**
     * 确定性触发一轮过期清理（单测用）；返回摘除数。
     */
    int sweepExpiredPeers() {
        return registry.sweepExpiredPeers();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        expirySweeper.interrupt();
        UdpTrackerServer udpServer = udp;
        if (udpServer != null) {
            udpServer.close();
        }
        server.stop(0);
        httpExecutor.shutdownNow();
        registry.clear();
    }
}
