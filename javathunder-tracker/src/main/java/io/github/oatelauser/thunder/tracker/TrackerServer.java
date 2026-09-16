package io.github.oatelauser.thunder.tracker;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;

/**
 * 生产级 HTTP Tracker 外观（BEP 3/23）：绑定 0.0.0.0（内网分发可用）、默认端口
 * {@link #DEFAULT_PORT}、announce 间隔 {@link #DEFAULT_ANNOUNCE_INTERVAL_SECONDS}。
 * 与 {@link EmbeddedTracker}（回环、interval=2s 的测试形态）共用同一实现，
 * 差异仅在绑定面与默认间隔。
 *
 * <p>能力：固定端口、Peer 过期清理（announceInterval × 2 无 announce 即摘除）、
 * {@code event=stopped} 立即摘除、多 swarm 并发、{@link #stats()} 可观测统计。
 * 可执行 jar 入口见 {@link TrackerMain}。
 */
public final class TrackerServer implements AutoCloseable {

    /** BitTorrent 客户端默认监听端口段起点。 */
    public static final int DEFAULT_PORT = 6881;
    /** BEP 3 常规 announce 间隔（30 分钟）。 */
    public static final int DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 1800;

    private final EmbeddedTracker delegate;

    private TrackerServer(EmbeddedTracker delegate) {
        this.delegate = delegate;
    }

    /** 默认形态：0.0.0.0:6881，interval=1800s。 */
    public static TrackerServer start() throws IOException {
        return start(DEFAULT_PORT, DEFAULT_ANNOUNCE_INTERVAL_SECONDS);
    }

    /** 固定端口，默认间隔。 */
    public static TrackerServer start(int port) throws IOException {
        return start(port, DEFAULT_ANNOUNCE_INTERVAL_SECONDS);
    }

    /**
     * 完全控制：port=0 随机；announceIntervalSeconds 决定响应 interval 与
     * 过期阈值（×2）。
     */
    public static TrackerServer start(int port, int announceIntervalSeconds) throws IOException {
        return new TrackerServer(EmbeddedTracker.start(
                wildcardAddress(), port, announceIntervalSeconds));
    }

    private static InetAddress wildcardAddress() {
        try {
            return InetAddress.getByAddress(new byte[]{0, 0, 0, 0});
        } catch (IOException e) {
            throw new AssertionError("unreachable: literal 0.0.0.0", e);
        }
    }

    /** 实际监听端口。 */
    public int port() {
        return delegate.port();
    }

    /** 回环形态的 announce URL（本机验证用；对内网其他主机用它的地址 + {@link #port()}）。 */
    public String announceUrl() {
        return delegate.announceUrl();
    }

    /** 每个 info-hash（hex）的 seeders/leechers/总数快照；空 swarm 不出现。 */
    public Map<String, EmbeddedTracker.SwarmStats> stats() {
        return delegate.stats();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
