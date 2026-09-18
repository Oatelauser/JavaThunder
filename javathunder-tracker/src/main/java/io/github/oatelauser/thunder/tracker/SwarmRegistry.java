package io.github.oatelauser.thunder.tracker;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

/**
 * Swarm 状态与 announce 领域逻辑（无 I/O）：Peer 登记/续期/摘除、过期清理、
 * 白名单、completed 累计、统计快照与 compact peers 生成。
 * HTTP 与 UDP 两个传输入口共享本状态。
 */
final class SwarmRegistry {

    /** compact peer 条目宽度 = 4B IPv4 + 2B 端口（BEP 23）。 */
    private static final int COMPACT_PEER_BYTES = 6;

    /**
     * swarm 成员：lastSeen 驱动过期；sticky（直接注册）不过期。
     */
    private static final class Peer {
        volatile long lastSeenMillis;
        volatile boolean seeder;
        volatile boolean sticky;

        Peer(long now, boolean seeder, boolean sticky) {
            this.lastSeenMillis = now;
            this.seeder = seeder;
            this.sticky = sticky;
        }
    }

    /**
     * announce 作用后的快照：全量计数 + 排除 self 的 IPv4 compact peers。
     */
    record SwarmView(int seeders, int leechers, byte[] peersCompact) {
    }

    /**
     * 一次 announce 的领域参数（HTTP/UDP 各自解析后传入）。
     */
    record Announce(byte[] infoHash, InetSocketAddress self, boolean seeder,
                    boolean stopped, boolean completed, int maxPeers) {
    }

    private final int announceIntervalSeconds;
    private final ConcurrentMap<String, ConcurrentMap<InetSocketAddress, Peer>> swarms =
            new ConcurrentHashMap<>();
    /**
     * info-hash(hex) → completed 累计；独立于 swarm 存活。
     */
    private final ConcurrentMap<String, AtomicLong> downloads = new ConcurrentHashMap<>();
    /**
     * null = 白名单关闭（全放行）；元素为 info-hash hex。
     */
    private volatile @Nullable Set<String> whitelist;

    SwarmRegistry(int announceIntervalSeconds) {
        this.announceIntervalSeconds = announceIntervalSeconds;
    }

    long expiryMillis() {
        return announceIntervalSeconds * 2000L;
    }

    // ---- 白名单 ----

    /**
     * 拒绝文案；放行返回 null。
     */
    @Nullable
    String denyReason(byte[] infoHash) {
        Set<String> allowed = whitelist;
        if (allowed == null) {
            return null;
        }
        return allowed.contains(HexFormat.of().formatHex(infoHash)) ? null : "torrent not registered";
    }

    void enableWhitelist(Collection<byte[]> infoHashes) {
        Set<String> hexes = ConcurrentHashMap.newKeySet();
        for (byte[] infoHash : infoHashes) {
            if (infoHash == null || infoHash.length != 20) {
                throw new IllegalArgumentException("info-hash must be 20 bytes");
            }
            hexes.add(HexFormat.of().formatHex(infoHash));
        }
        this.whitelist = hexes;
    }

    void disableWhitelist() {
        this.whitelist = null;
    }

    boolean whitelistEnabled() {
        return whitelist != null;
    }

    // ---- announce 作用 ----

    /**
     * stopped 摘除；否则登记/续期（left=0 → seeder）。downloaded 在 completed
     * 事件或该 Peer 首次转为 seeder 时累计一次。返回全量计数 + 排除 self 的
     * compact peers（stopped 时 peers 为空；maxPeers &gt; 0 截断）。
     */
    SwarmView apply(Announce announce) {
        ConcurrentMap<InetSocketAddress, Peer> swarm = swarmOf(announce.infoHash());
        if (announce.stopped()) {
            swarm.remove(announce.self());
            return view(swarm, null, -1);
        }
        long now = System.currentTimeMillis();
        boolean[] becameSeeder = { false };
        swarm.compute(announce.self(), (address, existing) -> {
            if (existing == null) {
                becameSeeder[0] = announce.seeder();
                return new Peer(now, announce.seeder(), false);
            }
            becameSeeder[0] = announce.seeder() && !existing.seeder;
            existing.lastSeenMillis = now;
            existing.seeder = announce.seeder();
            return existing;
        });
        if (announce.completed() || becameSeeder[0]) {
            downloads.computeIfAbsent(HexFormat.of().formatHex(announce.infoHash()), k -> new AtomicLong())
                    .incrementAndGet();
        }
        return view(swarm, announce.self(), announce.maxPeers());
    }

    /**
     * 种子方直接注册（FakeSeeder 形态）：无 announce 生命周期，不过期。
     */
    void register(byte[] infoHash, int port) {
        swarmOf(infoHash).compute(new InetSocketAddress("127.0.0.1", port), (address, existing) -> {
            if (existing == null) {
                return new Peer(System.currentTimeMillis(), true, true);
            }
            existing.lastSeenMillis = System.currentTimeMillis();
            existing.seeder = true;
            existing.sticky = true;
            return existing;
        });
    }

    /**
     * 摘除过期 Peer（now - lastSeen &gt; expiry，sticky 除外），回收空 swarm；返回摘除数。
     */
    int sweepExpiredPeers() {
        long now = System.currentTimeMillis();
        long expiry = expiryMillis();
        int evicted = 0;
        for (Map.Entry<String, ConcurrentMap<InetSocketAddress, Peer>> entry : swarms.entrySet()) {
            ConcurrentMap<InetSocketAddress, Peer> swarm = entry.getValue();
            // 弱一致遍历：并发 announce 新增的 Peer 最迟下一轮被评估
            for (Peer peer : swarm.values()) {
                if (!peer.sticky && now - peer.lastSeenMillis > expiry) {
                    if (swarm.values().remove(peer)) {
                        evicted++;
                    }
                }
            }
            if (swarm.isEmpty()) {
                swarms.remove(entry.getKey(), swarm); // 条件移除防误删替换者
            }
        }
        return evicted;
    }

    // ---- 快照与统计 ----

    /**
     * 每个 info-hash（hex）的 seeders/leechers/总数；空 swarm 不出现。
     */
    Map<String, EmbeddedTracker.SwarmStats> stats() {
        Map<String, EmbeddedTracker.SwarmStats> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, ConcurrentMap<InetSocketAddress, Peer>> entry : swarms.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                SwarmView counted = view(entry.getValue(), null, -1);
                snapshot.put(entry.getKey(), new EmbeddedTracker.SwarmStats(
                        counted.seeders(), counted.leechers(),
                        counted.seeders() + counted.leechers()));
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 单 swarm 的 scrape 计数：complete/downloaded/incomplete；未知 hash 全零。
     */
    Map<String, Long> scrapeEntry(String hex) {
        ConcurrentMap<InetSocketAddress, Peer> swarm = swarms.get(hex);
        SwarmView counted = swarm == null ? new SwarmView(0, 0, new byte[0]) : view(swarm, null, -1);
        AtomicLong downloaded = downloads.get(hex);
        Map<String, Long> entry = new HashMap<>();
        entry.put("complete", (long) counted.seeders());
        entry.put("downloaded", downloaded == null ? 0L : downloaded.get());
        entry.put("incomplete", (long) counted.leechers());
        return entry;
    }

    /**
     * 全部已知 info-hash（活动 swarm ∪ 有 completed 计数者）。
     */
    Set<String> knownHashes() {
        Set<String> hexes = new LinkedHashSet<>(swarms.keySet());
        hexes.addAll(downloads.keySet());
        return hexes;
    }

    /**
     * 全局 completed 计数总和。
     */
    long totalDownloads() {
        long total = 0;
        for (AtomicLong counter : downloads.values()) {
            total += counter.get();
        }
        return total;
    }

    void clear() {
        swarms.clear();
    }

    private ConcurrentMap<InetSocketAddress, Peer> swarmOf(byte[] infoHash) {
        return swarms.computeIfAbsent(HexFormat.of().formatHex(infoHash), k -> new ConcurrentHashMap<>());
    }

    /**
     * 全量计数 + compact peers；self=null 表示不含任何 peer（stopped 响应语义）。
     */
    private SwarmView view(ConcurrentMap<InetSocketAddress, Peer> swarm,
            @Nullable InetSocketAddress self, int maxPeers) {
        int seeders = 0;
        int leechers = 0;
        ByteArrayOutputStream peers = new ByteArrayOutputStream();
        for (Map.Entry<InetSocketAddress, Peer> entry : swarm.entrySet()) {
            if (entry.getValue().seeder) {
                seeders++;
            } else {
                leechers++;
            }
            if (self == null || entry.getKey().equals(self)) {
                continue;
            }
            if (maxPeers > 0 && peers.size() / COMPACT_PEER_BYTES >= maxPeers) {
                continue;
            }
            byte[] address = entry.getKey().getAddress().getAddress();
            if (address.length != 4) {
                continue; // compact（BEP 23）仅 IPv4
            }
            peers.writeBytes(address);
            // 端口按网络序（大端）两字节压入 compact 条目
            peers.write(entry.getKey().getPort() >> 8);
            peers.write(entry.getKey().getPort() & 0xFF);
        }
        return new SwarmView(seeders, leechers, peers.toByteArray());
    }
}
