package io.github.oatelauser.thunder.tracker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内嵌 Tracker：HTTP announce（BEP 3/23）+ UDP announce（BEP 15）+ scrape
 * （BEP 48）+ 白名单 + /stats、/metrics 可观测端点。内存 Peer 表，compact 响应。
 *
 * <p>生产化能力：固定端口（{@link #start(int)}）、Peer 过期清理（后台虚拟线程，
 * {@code now - lastSeen > announceInterval × 2} 摘除）、{@code event=stopped}
 * 立即摘除、按 info-hash 的 seeders/leechers 统计（{@link #stats()}）与
 * completed 累计（scrape 的 downloaded）。announce 响应用 core 的 bencode 编码
 * （字典键规范形排序）。
 *
 * <p><b>UDP（BEP 15 服务端）</b>：默认关闭，{@link #enableUdp(int)} 开启
 * （port=0 与 HTTP 同端口）。connect 无状态——每次 connect 发回新生成的
 * connection_id，announce 不校验之（客户端可任意缓存）。announce 请求布局
 * 镜像 core 的 {@code UdpTrackerClient}：connection_id(8)/action(4)/
 * transaction_id(4)/info_hash(20)/peer_id(20)/downloaded(8)/uploaded(8)/
 * left(8)/event(4)/port(4)/numwant(4)（标准 BEP 15 的 ip/key 字段位置被该
 * 客户端用于 port/numwant，服务端对齐之）。报文不完整或 action 未知一律
 * 静默丢弃（防放大，BEP 15 安全建议）。事务 ID 原样回带。
 *
 * <p><b>scrape（BEP 48）</b>：{@code GET /scrape?info_hash=...}（可重复多次，
 * 缺省返回全部 swarm）；未知 hash 返回全零条目。downloaded 在 completed 事件
 * 或 Peer 首次以 left=0 出现时累计。
 *
 * <p><b>白名单</b>：默认关闭（全放行）；{@link #enableWhitelist(Collection)}
 * 后非白名单 announce 收到 failure reason "torrent not registered"
 * （HTTP 为 bencode failure，UDP 为 action=3 error 包）。
 *
 * <p>testkit 兼容默认：{@link #start()} 绑定回环、随机端口、interval=2s、
 * 无 UDP 无白名单；{@link #register(byte[], int)} 直接注册的 Peer 无 announce
 * 生命周期，不过期。{@link TrackerServer} 是生产外观（0.0.0.0 绑定、长间隔），
 * 两者共用本实现。
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

    /** announce 作用后的 swarm 快照：全量计数 + 排除 self 的 IPv4 compact peers。 */
    private record SwarmSnapshot(int seeders, int leechers, byte[] peersCompact) {
    }

    private static final long MAX_SWEEP_PERIOD_MILLIS = 30_000;
    private static final long MIN_SWEEP_PERIOD_MILLIS = 250;

    /** BEP 15 connect 请求的 protocol_id（常量魔数）。 */
    static final long CONNECT_PROTOCOL_ID = 0x41727101980AL;
    static final int ACTION_CONNECT = 0;
    static final int ACTION_ANNOUNCE = 1;
    static final int ACTION_ERROR = 3;
    /** BEP 15 event 数值：1=completed，3=stopped。 */
    static final int UDP_EVENT_COMPLETED = 1;
    static final int UDP_EVENT_STOPPED = 3;
    /** core UdpTrackerClient 布局的 announce 请求最小长度（端口 84..88、numwant 88..92）。 */
    static final int UDP_ANNOUNCE_REQUEST_BYTES = 92;

    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final int announceIntervalSeconds;
    private final long sweepPeriodMillis;
    private final Thread expirySweeper;
    private final ConcurrentMap<String, ConcurrentMap<InetSocketAddress, Peer>> swarms =
            new ConcurrentHashMap<>();
    /** info-hash(hex) → completed 累计；独立于 swarms 存活（swarm 清空后仍保留）。 */
    private final ConcurrentMap<String, AtomicLong> downloads = new ConcurrentHashMap<>();
    private final AtomicLong httpAnnounces = new AtomicLong();
    private final AtomicLong udpAnnounces = new AtomicLong();
    private final AtomicLong scrapes = new AtomicLong();
    /** null = 白名单关闭（默认全放行）；元素为 info-hash hex。 */
    private volatile @Nullable Set<String> whitelist;
    private volatile @Nullable DatagramSocket udpSocket;
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
        server.createContext("/announce", tracker::handleAnnounce);
        server.createContext("/scrape", tracker::handleScrape);
        server.createContext("/stats", tracker::handleStats);
        server.createContext("/metrics", tracker::handleMetrics);
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
     * 开启 UDP announce（BEP 15 服务端，默认关闭）：port &gt; 0 指定监听端口，
     * port = 0 与 HTTP 同端口；绑定地址与 HTTP 一致。返回实际监听端口。
     * 每个实例只能开启一次；{@link #close()} 一并释放。
     */
    public synchronized int enableUdp(int port) throws IOException {
        if (closed) {
            throw new IllegalStateException("tracker is closed");
        }
        if (udpSocket != null) {
            throw new IllegalStateException("udp already enabled on port " + udpSocket.getLocalPort());
        }
        int bindPort = port > 0 ? port : server.getAddress().getPort();
        DatagramSocket socket = new DatagramSocket(
                new InetSocketAddress(server.getAddress().getAddress(), bindPort));
        udpSocket = socket;
        Thread.ofVirtual().name("tracker-udp-" + bindPort).start(() -> serveUdp(socket));
        return bindPort;
    }

    /** UDP 监听端口；未开启返回 -1。 */
    public int udpPort() {
        DatagramSocket socket = udpSocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    /** 回环形态的 UDP announce URL（{@code udp://127.0.0.1:<port>/announce}）；未开启返回 null。 */
    public @Nullable String udpAnnounceUrl() {
        int port = udpPort();
        return port < 0 ? null : "udp://127.0.0.1:" + port + "/announce";
    }

    /**
     * 启用白名单：仅列出的 info-hash 可 announce（scrape/统计不受限）。
     * 重复调用替换旧表；元素须为 20 字节。
     */
    public void enableWhitelist(Collection<byte[]> infoHashes) {
        Set<String> hexes = ConcurrentHashMap.newKeySet();
        for (byte[] infoHash : infoHashes) {
            if (infoHash == null || infoHash.length != 20) {
                throw new IllegalArgumentException("info-hash must be 20 bytes");
            }
            hexes.add(HexFormat.of().formatHex(infoHash));
        }
        this.whitelist = hexes;
    }

    /** 关闭白名单（默认：全放行）。 */
    public void disableWhitelist() {
        this.whitelist = null;
    }

    /** 白名单是否启用。 */
    public boolean whitelistEnabled() {
        return whitelist != null;
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
            if (!entry.getValue().isEmpty()) {
                SwarmSnapshot counted = snapshot(entry.getValue());
                snapshot.put(entry.getKey(), new SwarmStats(
                        counted.seeders(), counted.leechers(),
                        counted.seeders() + counted.leechers()));
            }
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

    // ---- announce 核心（HTTP 与 UDP 共享） ----

    /** 白名单拒绝文案；放行返回 null。 */
    private @Nullable String whitelistFailure(byte[] infoHash) {
        Set<String> allowed = whitelist;
        if (allowed == null) {
            return null;
        }
        return allowed.contains(HexFormat.of().formatHex(infoHash)) ? null : "torrent not registered";
    }

    /**
     * announce 侧共享副作用：stopped 摘除；否则登记/续期（left=0 → seeder）。
     * downloaded 在 completed 事件或该 Peer 首次转为 seeder（含新登记即 left=0）时
     * 累计一次。返回全 swarm 计数 + 排除 self 的 IPv4 compact peers
     * （stopped 时 peers 为空；maxPeers &gt; 0 时截断）。
     */
    private SwarmSnapshot applyAnnounce(byte[] infoHash, InetSocketAddress self, boolean seeder,
                                        boolean stopped, boolean completed, int maxPeers) {
        ConcurrentMap<InetSocketAddress, Peer> swarm = swarm(infoHash);
        if (stopped) {
            swarm.remove(self);
            return snapshot(swarm, null, -1);
        }
        long now = System.currentTimeMillis();
        boolean[] becameSeeder = {false};
        swarm.compute(self, (address, existing) -> {
            if (existing == null) {
                becameSeeder[0] = seeder;
                return new Peer(now, seeder, false);
            }
            becameSeeder[0] = seeder && !existing.seeder;
            existing.lastSeenMillis = now;
            existing.seeder = seeder;
            return existing;
        });
        if (completed || becameSeeder[0]) {
            downloads.computeIfAbsent(HexFormat.of().formatHex(infoHash), k -> new AtomicLong())
                    .incrementAndGet();
        }
        return snapshot(swarm, self, maxPeers);
    }

    /** 全量计数 + compact peers；self=null 表示不含任何 peer（stopped 响应语义）。 */
    private SwarmSnapshot snapshot(ConcurrentMap<InetSocketAddress, Peer> swarm) {
        return snapshot(swarm, null, -1);
    }

    private SwarmSnapshot snapshot(ConcurrentMap<InetSocketAddress, Peer> swarm,
                                   @Nullable InetSocketAddress self, int maxPeers) {
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
            if (maxPeers > 0 && peers.size() / 6 >= maxPeers) {
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
        return new SwarmSnapshot(seeders, leechers, peers.toByteArray());
    }

    // ---- HTTP handlers ----

    private void handleAnnounce(HttpExchange exchange) throws IOException {
        try {
            Map<String, List<byte[]>> params = Query.parse(exchange.getRequestURI().getRawQuery());
            byte[] infoHash = Query.first(params, "info_hash");
            byte[] portBytes = Query.first(params, "port");
            if (infoHash == null || infoHash.length != 20 || portBytes == null) {
                respond(exchange, Bencode.encode(failure("invalid announce")), null);
                return;
            }
            int port;
            try {
                port = Integer.parseInt(new String(portBytes, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                respond(exchange, Bencode.encode(failure("invalid announce")), null);
                return;
            }
            httpAnnounces.incrementAndGet();
            String denied = whitelistFailure(infoHash);
            if (denied != null) {
                respond(exchange, Bencode.encode(failure(denied)), null);
                return;
            }
            String remoteIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            InetSocketAddress self = new InetSocketAddress(remoteIp, port);
            String event = Query.textOf(Query.first(params, "event"));
            SwarmSnapshot snapshot = applyAnnounce(infoHash, self, seederByLeft(Query.first(params, "left")),
                    "stopped".equals(event), "completed".equals(event), -1);
            respond(exchange, Bencode.encode(response(snapshot)), null);
        } catch (RuntimeException e) {
            respond(exchange, Bencode.encode(failure("tracker error: " + e)), null);
        }
    }

    /** scrape（BEP 48）：?info_hash=... 可重复；缺省返回全部已知 swarm；未知 hash 全零。 */
    private void handleScrape(HttpExchange exchange) throws IOException {
        try {
            Map<String, List<byte[]>> params = Query.parse(exchange.getRequestURI().getRawQuery());
            scrapes.incrementAndGet();
            List<byte[]> requested = params.get("info_hash");
            Map<BString, BencodeValue> files = new HashMap<>();
            if (requested == null || requested.isEmpty()) {
                for (String hex : swarms.keySet()) {
                    files.put(new BString(HexFormat.of().parseHex(hex)), fileEntry(hex));
                }
            } else {
                for (byte[] infoHash : requested) {
                    if (infoHash != null && infoHash.length == 20) {
                        files.put(new BString(infoHash),
                                fileEntry(HexFormat.of().formatHex(infoHash)));
                    }
                }
            }
            BDict body = BDict.of(Map.of(BString.of("files"), BDict.of(files)));
            respond(exchange, Bencode.encode(body), null);
        } catch (RuntimeException e) {
            respond(exchange, Bencode.encode(failure("tracker error: " + e)), null);
        }
    }

    /** 单 swarm 的 scrape 条目：complete/downloaded/incomplete。 */
    private BDict fileEntry(String hex) {
        ConcurrentMap<InetSocketAddress, Peer> swarm = swarms.get(hex);
        SwarmSnapshot counted = swarm == null
                ? new SwarmSnapshot(0, 0, new byte[0])
                : snapshot(swarm);
        AtomicLong downloaded = downloads.get(hex);
        return BDict.of(Map.of(
                BString.of("complete"), new BInteger(counted.seeders()),
                BString.of("downloaded"), new BInteger(downloaded == null ? 0 : downloaded.get()),
                BString.of("incomplete"), new BInteger(counted.leechers())));
    }

    /** 人类可读统计页：全局汇总 + 每 info-hash 表。 */
    private void handleStats(HttpExchange exchange) throws IOException {
        try {
            Map<String, SwarmStats> perSwarm = stats();
            Set<String> hexes = new LinkedHashSet<>(perSwarm.keySet());
            hexes.addAll(downloads.keySet());
            long totalSeeders = 0;
            long totalLeechers = 0;
            long totalDownloads = 0;
            for (SwarmStats swarm : perSwarm.values()) {
                totalSeeders += swarm.seeders();
                totalLeechers += swarm.leechers();
            }
            for (AtomicLong counter : downloads.values()) {
                totalDownloads += counter.get();
            }
            StringBuilder html = new StringBuilder(2048);
            html.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">")
                    .append("<title>javathunder-tracker stats</title>\n")
                    .append("<style>body{font-family:system-ui,sans-serif;margin:2rem}")
                    .append("table{border-collapse:collapse;margin-bottom:1.5rem}")
                    .append("td,th{border:1px solid #ccc;padding:.25rem .9rem;text-align:left}")
                    .append("th{background:#f4f4f4}code{font-size:.9em}</style>\n")
                    .append("</head>\n<body>\n<h1>javathunder-tracker</h1>\n");
            html.append("<table>\n");
            row(html, "active swarms", perSwarm.size());
            row(html, "peers (seed / leech)", totalSeeders + " / " + totalLeechers);
            row(html, "downloads (completed)", totalDownloads);
            row(html, "announces (http / udp)", httpAnnounces.get() + " / " + udpAnnounces.get());
            row(html, "scrapes", scrapes.get());
            row(html, "udp", udpPort() < 0 ? "disabled" : "port " + udpPort());
            row(html, "whitelist", whitelistEnabled() ? "enabled" : "disabled");
            html.append("</table>\n");
            html.append("<table>\n<tr><th>info-hash</th><th>seeders</th><th>leechers</th>")
                    .append("<th>peers</th><th>downloads</th></tr>\n");
            for (String hex : hexes) {
                SwarmStats swarm = perSwarm.get(hex);
                AtomicLong downloaded = downloads.get(hex);
                html.append("<tr><td><code>").append(hex).append("</code></td><td>")
                        .append(swarm == null ? 0 : swarm.seeders()).append("</td><td>")
                        .append(swarm == null ? 0 : swarm.leechers()).append("</td><td>")
                        .append(swarm == null ? 0 : swarm.total()).append("</td><td>")
                        .append(downloaded == null ? 0 : downloaded.get()).append("</td></tr>\n");
            }
            html.append("</table>\n</body>\n</html>\n");
            respond(exchange, html.toString().getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
        } catch (RuntimeException e) {
            respond(exchange, Bencode.encode(failure("tracker error: " + e)), null);
        }
    }

    private static void row(StringBuilder html, String key, Object value) {
        html.append("<tr><th>").append(key).append("</th><td>").append(value).append("</td></tr>\n");
    }

    /** Prometheus 文本格式（0.0.4）暴露：swarm peers/downloads、announce/scrape 计数、活跃 swarm。 */
    private void handleMetrics(HttpExchange exchange) throws IOException {
        try {
            Map<String, SwarmStats> perSwarm = stats();
            StringBuilder text = new StringBuilder(2048);
            text.append("# HELP javathunder_tracker_swarm_peers Peers currently registered per swarm.\n")
                    .append("# TYPE javathunder_tracker_swarm_peers gauge\n");
            for (Map.Entry<String, SwarmStats> entry : perSwarm.entrySet()) {
                text.append("javathunder_tracker_swarm_peers{role=\"seed\",info_hash=\"")
                        .append(entry.getKey()).append("\"} ").append(entry.getValue().seeders()).append('\n');
                text.append("javathunder_tracker_swarm_peers{role=\"leech\",info_hash=\"")
                        .append(entry.getKey()).append("\"} ").append(entry.getValue().leechers()).append('\n');
            }
            text.append("# HELP javathunder_tracker_swarm_downloads_total Completed downloads per swarm.\n")
                    .append("# TYPE javathunder_tracker_swarm_downloads_total counter\n");
            Set<String> hexes = new LinkedHashSet<>(perSwarm.keySet());
            hexes.addAll(downloads.keySet());
            for (String hex : hexes) {
                AtomicLong downloaded = downloads.get(hex);
                text.append("javathunder_tracker_swarm_downloads_total{info_hash=\"")
                        .append(hex).append("\"} ")
                        .append(downloaded == null ? 0 : downloaded.get()).append('\n');
            }
            text.append("# HELP javathunder_tracker_announces_total Announce requests processed, by transport.\n")
                    .append("# TYPE javathunder_tracker_announces_total counter\n")
                    .append("javathunder_tracker_announces_total{transport=\"http\"} ")
                    .append(httpAnnounces.get()).append('\n')
                    .append("javathunder_tracker_announces_total{transport=\"udp\"} ")
                    .append(udpAnnounces.get()).append('\n')
                    .append("# HELP javathunder_tracker_scrapes_total Scrape requests processed.\n")
                    .append("# TYPE javathunder_tracker_scrapes_total counter\n")
                    .append("javathunder_tracker_scrapes_total ").append(scrapes.get()).append('\n')
                    .append("# HELP javathunder_tracker_active_swarms Swarms with at least one registered peer.\n")
                    .append("# TYPE javathunder_tracker_active_swarms gauge\n")
                    .append("javathunder_tracker_active_swarms ").append(perSwarm.size()).append('\n');
            respond(exchange, text.toString().getBytes(StandardCharsets.UTF_8),
                    "text/plain; version=0.0.4; charset=utf-8");
        } catch (RuntimeException e) {
            respond(exchange, Bencode.encode(failure("tracker error: " + e)), null);
        }
    }

    // ---- UDP（BEP 15 服务端） ----

    private void serveUdp(DatagramSocket socket) {
        byte[] buffer = new byte[2048];
        while (!closed) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                ByteBuffer in = ByteBuffer.wrap(buffer, 0, packet.getLength()).order(ByteOrder.BIG_ENDIAN);
                handleUdpPacket(socket, packet, in);
            } catch (IOException e) {
                return; // socket 关闭或不可恢复：退出循环
            }
        }
    }

    private void handleUdpPacket(DatagramSocket socket, DatagramPacket packet, ByteBuffer in) {
        if (in.remaining() < 16) {
            return; // 头部不完整：忽略
        }
        long connectionId = in.getLong(0);
        int action = in.getInt(8);
        int transactionId = in.getInt(12);
        switch (action) {
            case ACTION_CONNECT -> {
                if (connectionId != CONNECT_PROTOCOL_ID || in.remaining() != 16) {
                    return; // 非法 connect：忽略
                }
                ByteBuffer out = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                        .putInt(ACTION_CONNECT)
                        .putInt(transactionId)
                        .putLong(ThreadLocalRandom.current().nextLong());
                reply(socket, packet, out.array());
            }
            case ACTION_ANNOUNCE -> handleUdpAnnounce(socket, packet, transactionId);
            default -> {
                // 未知 action：静默丢弃（BEP 15 安全建议：不回包防放大）
            }
        }
    }

    private void handleUdpAnnounce(DatagramSocket socket, DatagramPacket packet, int transactionId) {
        ByteBuffer in = ByteBuffer.wrap(packet.getData(), 0, packet.getLength()).order(ByteOrder.BIG_ENDIAN);
        if (in.remaining() < UDP_ANNOUNCE_REQUEST_BYTES) {
            return; // 布局不完整：忽略
        }
        byte[] infoHash = new byte[20];
        in.position(16).get(infoHash);
        long left = in.position(72).getLong();
        int event = in.getInt(80);
        int port = in.getInt(84) & 0xFFFF; // core UdpTrackerClient 布局：port 为 int
        int numwant = in.getInt(88);
        String denied = whitelistFailure(infoHash);
        if (denied != null) {
            udpError(socket, packet, transactionId, denied);
            return;
        }
        udpAnnounces.incrementAndGet();
        InetSocketAddress self = new InetSocketAddress(packet.getAddress().getHostAddress(), port);
        SwarmSnapshot snapshot = applyAnnounce(infoHash, self, left == 0,
                event == UDP_EVENT_STOPPED, event == UDP_EVENT_COMPLETED, numwant > 0 ? numwant : -1);
        // BEP 15 应答头：action/transaction_id 之后为 interval/leechers/seeders（与 HTTP
        // 的 complete/incomplete 顺序相反，leechers 在前），peer 紧凑表从偏移 20 起
        ByteBuffer out = ByteBuffer.allocate(20 + snapshot.peersCompact().length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(ACTION_ANNOUNCE)
                .putInt(transactionId)
                .putInt(announceIntervalSeconds)
                .putInt(snapshot.leechers())
                .putInt(snapshot.seeders())
                .put(snapshot.peersCompact());
        reply(socket, packet, out.array());
    }

    private void udpError(DatagramSocket socket, DatagramPacket packet, int transactionId, String message) {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(8 + text.length).order(ByteOrder.BIG_ENDIAN)
                .putInt(ACTION_ERROR)
                .putInt(transactionId)
                .put(text);
        reply(socket, packet, out.array());
    }

    private static void reply(DatagramSocket socket, DatagramPacket request, byte[] wire) {
        try {
            socket.send(new DatagramPacket(wire, wire.length, request.getAddress(), request.getPort()));
        } catch (IOException ignored) {
            // 对端消失/网络抖动：UDP 丢包即弃
        }
    }

    // ---- 共享工具 ----

    private ConcurrentMap<InetSocketAddress, Peer> swarm(byte[] infoHash) {
        return swarms.computeIfAbsent(HexFormat.of().formatHex(infoHash), k -> new ConcurrentHashMap<>());
    }

    /** left=0 → seeder；缺失按 leecher 处理。 */
    private static boolean seederByLeft(@Nullable byte[] leftBytes) {
        if (leftBytes == null) {
            return false;
        }
        try {
            return Long.parseLong(new String(leftBytes, StandardCharsets.US_ASCII)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 标准 announce 响应：interval/complete/incomplete/peers（compact）。 */
    private BDict response(SwarmSnapshot snapshot) {
        return BDict.of(Map.of(
                BString.of("complete"), new BInteger(snapshot.seeders()),
                BString.of("incomplete"), new BInteger(snapshot.leechers()),
                BString.of("interval"), new BInteger(announceIntervalSeconds),
                BString.of("peers"), new BString(snapshot.peersCompact())));
    }

    private static BDict failure(String reason) {
        return BDict.of(Map.of(BString.of("failure reason"), BString.of(reason)));
    }

    private static void respond(HttpExchange exchange, byte[] body, @Nullable String contentType)
            throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        expirySweeper.interrupt();
        DatagramSocket socket = udpSocket;
        if (socket != null) {
            socket.close(); // 阻塞在 receive 的监听线程随之退出
        }
        server.stop(0);
        httpExecutor.shutdownNow();
        swarms.clear();
    }

    /**
     * raw query 解析（%XX → 原始字节）；同名键（如多次 info_hash）聚合为列表。
     */
    private static final class Query {
        static Map<String, List<byte[]>> parse(@Nullable String rawQuery) {
            Map<String, List<byte[]>> params = new LinkedHashMap<>();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return params;
            }
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                params.computeIfAbsent(new String(decode(pair.substring(0, eq)), StandardCharsets.UTF_8),
                        k -> new ArrayList<>()).add(decode(pair.substring(eq + 1)));
            }
            return params;
        }

        static @Nullable byte[] first(Map<String, List<byte[]>> params, String key) {
            List<byte[]> values = params.get(key);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        static @Nullable String textOf(@Nullable byte[] bytes) {
            return bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII);
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
