package io.github.oatelauser.thunder.tracker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内嵌 HTTP Tracker（BEP 3/23）：内存 Peer 表，compact 响应。
 *
 * <p>生产化能力：固定端口（{@link #start(int)}）、Peer 过期清理（后台虚拟线程，
 * {@code now - lastSeen > announceInterval × 2} 摘除）、{@code event=stopped}
 * 立即摘除、按 info-hash 的 seeders/leechers 统计（{@link #stats()}）。
 * announce 响应用 core 的 bencode 编码（字典键规范形排序）。
 *
 * <p>testkit 兼容默认：{@link #start()} 绑定回环、随机端口、interval=2s；
 * {@link #register(byte[], int)} 直接注册的 Peer 无 announce 生命周期，不过期。
 * {@link TrackerServer} 是生产外观（0.0.0.0 绑定、长间隔），两者共用本实现。
 */
public final class EmbeddedTracker implements AutoCloseable {

    /** 每个 swarm 的可观测快照：做种 / 下载 / 总数。 */
    public record SwarmStats(int seeders, int leechers, int total) {
    }

    /** swarm 成员：lastSeen 驱动过期；sticky（直接注册）不过期。 */
    static final class Peer {
        volatile long lastSeenMillis;
        volatile boolean seeder;
        volatile boolean sticky;

        Peer(long now, boolean seeder, boolean sticky) {
            this.lastSeenMillis = now;
            this.seeder = seeder;
            this.sticky = sticky;
        }
    }

    private static final long MAX_SWEEP_PERIOD_MILLIS = 30_000;
    private static final long MIN_SWEEP_PERIOD_MILLIS = 250;

    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final int announceIntervalSeconds;
    private final long sweepPeriodMillis;
    private final Thread expirySweeper;
    private final ConcurrentMap<String, ConcurrentMap<InetSocketAddress, Peer>> swarms =
            new ConcurrentHashMap<>();
    private volatile boolean closed;

    private EmbeddedTracker(HttpServer server, ExecutorService httpExecutor, int announceIntervalSeconds) {
        this.server = server;
        this.httpExecutor = httpExecutor;
        this.announceIntervalSeconds = announceIntervalSeconds;
        this.sweepPeriodMillis = Math.clamp(
                announceIntervalSeconds * 1000L / 2, MIN_SWEEP_PERIOD_MILLIS, MAX_SWEEP_PERIOD_MILLIS);
        this.expirySweeper = Thread.ofVirtual().name("tracker-peer-expiry").unstarted(() -> {
            while (!closed) {
                try {
                    Thread.sleep(sweepPeriodMillis);
                } catch (InterruptedException e) {
                    return; // closed
                }
                try {
                    sweepExpiredPeers();
                } catch (RuntimeException ignored) {
                    // 单轮失败不终止清理线程
                }
            }
        });
    }

    /** testkit 兼容：回环 + 随机端口 + interval=2s。 */
    public static EmbeddedTracker start() throws IOException {
        return start(InetAddress.getLoopbackAddress(), 0, 2);
    }

    /** 固定端口（0 = 随机），回环 + interval=2s。 */
    public static EmbeddedTracker start(int port) throws IOException {
        return start(port, 2);
    }

    /** 固定端口（0 = 随机）+ 自定间隔（秒）：小间隔可加速过期类测试。回环绑定。 */
    public static EmbeddedTracker start(int port, int announceIntervalSeconds) throws IOException {
        return start(InetAddress.getLoopbackAddress(), port, announceIntervalSeconds);
    }

    /** 完全控制绑定地址 / 端口 / announce 间隔（{@link TrackerServer} 生产入口用它）。 */
    public static EmbeddedTracker start(InetAddress bindAddress, int port, int announceIntervalSeconds)
            throws IOException {
        if (announceIntervalSeconds <= 0) {
            throw new IllegalArgumentException("announceIntervalSeconds must be > 0");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 64);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        EmbeddedTracker tracker = new EmbeddedTracker(server, executor, announceIntervalSeconds);
        server.createContext("/announce", tracker::handle);
        server.start();
        tracker.expirySweeper.start();
        return tracker;
    }

    /** 实际监听端口（固定端口绑定时即传入值）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 回环形态的 announce URL（无论绑定地址为何，回环总是可达）。 */
    public String announceUrl() {
        return "http://127.0.0.1:" + port() + "/announce";
    }

    /**
     * 种子方直接注册（FakeSeeder 用，绕过 HTTP announce）：无 announce 生命周期，
     * 不参与过期清理。
     */
    public void register(byte[] infoHash, int port) {
        swarm(infoHash).compute(new InetSocketAddress("127.0.0.1", port), (address, existing) -> {
            if (existing == null) {
                return new Peer(System.currentTimeMillis(), true, true);
            }
            existing.lastSeenMillis = System.currentTimeMillis();
            existing.seeder = true;
            existing.sticky = true;
            return existing;
        });
    }

    /** 每个 info-hash（hex）的 seeders/leechers/总数快照；空 swarm 不出现。 */
    public Map<String, SwarmStats> stats() {
        Map<String, SwarmStats> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, ConcurrentMap<InetSocketAddress, Peer>> entry : swarms.entrySet()) {
            int seeders = 0;
            int leechers = 0;
            for (Peer peer : entry.getValue().values()) {
                if (peer.seeder) {
                    seeders++;
                } else {
                    leechers++;
                }
            }
            snapshot.put(entry.getKey(), new SwarmStats(seeders, leechers, seeders + leechers));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 摘除过期 Peer（now - lastSeen &gt; announceInterval × 2，sticky 除外），
     * 顺带回收空 swarm。返回本轮摘除数。包可见：单测确定性触发。
     */
    int sweepExpiredPeers() {
        long now = System.currentTimeMillis();
        long expiryMillis = announceIntervalSeconds * 2000L;
        int evicted = 0;
        for (Map.Entry<String, ConcurrentMap<InetSocketAddress, Peer>> entry : swarms.entrySet()) {
            ConcurrentMap<InetSocketAddress, Peer> swarm = entry.getValue();
            // 弱一致遍历：并发 announce 中新增的 Peer 最迟下一轮被评估
            for (Peer peer : swarm.values()) {
                if (!peer.sticky && now - peer.lastSeenMillis > expiryMillis) {
                    if (swarm.values().remove(peer)) {
                        evicted++;
                    }
                }
            }
            if (swarm.isEmpty()) {
                // 条件移除：仅在仍为本 map 时回收（被替换则由后续 announce 重建）
                swarms.remove(entry.getKey(), swarm);
            }
        }
        return evicted;
    }

    private ConcurrentMap<InetSocketAddress, Peer> swarm(byte[] infoHash) {
        return swarms.computeIfAbsent(HexFormat.of().formatHex(infoHash), k -> new ConcurrentHashMap<>());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            Map<String, byte[]> params = Query.parse(exchange.getRequestURI().getRawQuery());
            byte[] infoHash = params.get("info_hash");
            byte[] portBytes = params.get("port");
            if (infoHash == null || infoHash.length != 20 || portBytes == null) {
                respond(exchange, Bencode.encode(failure("invalid announce")));
                return;
            }
            int port;
            try {
                port = Integer.parseInt(new String(portBytes, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                respond(exchange, Bencode.encode(failure("invalid announce")));
                return;
            }
            String remoteIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            InetSocketAddress self = new InetSocketAddress(remoteIp, port);
            ConcurrentMap<InetSocketAddress, Peer> swarm = swarm(infoHash);
            boolean seeder = seederByLeft(params.get("left"));
            if (stopped(params.get("event"))) {
                swarm.remove(self);
                respond(exchange, Bencode.encode(response(swarm, null)));
                return;
            }
            long now = System.currentTimeMillis();
            swarm.compute(self, (address, existing) -> {
                if (existing == null) {
                    return new Peer(now, seeder, false);
                }
                existing.lastSeenMillis = now;
                existing.seeder = seeder;
                return existing;
            });
            respond(exchange, Bencode.encode(response(swarm, self)));
        } catch (RuntimeException e) {
            respond(exchange, Bencode.encode(failure("tracker error: " + e)));
        }
    }

    /** left=0 → seeder；缺失按 leecher 处理。 */
    private static boolean seederByLeft(byte[] leftBytes) {
        if (leftBytes == null) {
            return false;
        }
        try {
            return Long.parseLong(new String(leftBytes, StandardCharsets.US_ASCII)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean stopped(byte[] eventBytes) {
        return eventBytes != null && "stopped".equals(new String(eventBytes, StandardCharsets.US_ASCII));
    }

    /**
     * 标准 announce 响应：interval/complete/incomplete/peers（compact）。
     * 计数含 swarm 全体；self 非 null 时从 peers 里排除自己；
     * null（stopped 后）返回空 peers。
     */
    private BDict response(ConcurrentMap<InetSocketAddress, Peer> swarm, InetSocketAddress self) {
        int seeders = 0;
        int leechers = 0;
        ByteArrayOutputStream peers = new ByteArrayOutputStream();
        for (Map.Entry<InetSocketAddress, Peer> entry : swarm.entrySet()) {
            Peer peer = entry.getValue();
            if (peer.seeder) {
                seeders++;
            } else {
                leechers++;
            }
            if (self == null || entry.getKey().equals(self)) {
                continue;
            }
            byte[] address = entry.getKey().getAddress().getAddress();
            if (address.length != 4) {
                continue; // compact（BEP 23）仅 IPv4；IPv6 peer 无法表示，跳过
            }
            peers.writeBytes(address);
            peers.write(entry.getKey().getPort() >> 8);
            peers.write(entry.getKey().getPort() & 0xFF);
        }
        return BDict.of(Map.of(
                BString.of("complete"), new BInteger(seeders),
                BString.of("incomplete"), new BInteger(leechers),
                BString.of("interval"), new BInteger(announceIntervalSeconds),
                BString.of("peers"), new BString(peers.toByteArray())));
    }

    private static BDict failure(String reason) {
        return BDict.of(Map.of(BString.of("failure reason"), BString.of(reason)));
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        expirySweeper.interrupt();
        server.stop(0);
        httpExecutor.shutdownNow();
        swarms.clear();
    }

    /**
     * raw query 解析（%XX → 原始字节）。
     */
    private static final class Query {
        static Map<String, byte[]> parse(String rawQuery) {
            Map<String, byte[]> params = new ConcurrentHashMap<>();
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                params.put(new String(decode(pair.substring(0, eq)), StandardCharsets.UTF_8),
                        decode(pair.substring(eq + 1)));
            }
            return params;
        }

        static byte[] decode(String s) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < s.length(); ) {
                if (s.charAt(i) == '%') {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 3;
                } else {
                    out.write(s.charAt(i));
                    i++;
                }
            }
            return out.toByteArray();
        }
    }
}
