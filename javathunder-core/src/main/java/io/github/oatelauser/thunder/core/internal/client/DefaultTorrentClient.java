package io.github.oatelauser.thunder.core.internal.client;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.MagnetUri;
import io.github.oatelauser.thunder.api.PeerDiscoverySource;
import io.github.oatelauser.thunder.api.RestartVerifyMode;
import io.github.oatelauser.thunder.api.SeedOptions;
import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.engine.DownloadSession;
import io.github.oatelauser.thunder.core.internal.engine.MagnetDownloadTask;
import io.github.oatelauser.thunder.core.internal.engine.MetadataFetcher;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.core.internal.peer.transport.BlockingTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.NioTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.ratelimit.RateLimiter;
import io.github.oatelauser.thunder.core.internal.peer.PeerIds;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerClient;
import io.github.oatelauser.thunder.core.internal.tracker.UdpTrackerClient;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * {@link TorrentClient} 默认实现：全局资源 + 多任务编排 + 入站连接路由。
 */
public final class DefaultTorrentClient implements TorrentClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultTorrentClient.class);

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 便捷工厂：全部默认值。
     */
    public static DefaultTorrentClient create() throws IOException {
        return builder().build();
    }

    public static final class Builder implements TorrentClient.Builder {
        private int listenPort = 6881;
        private int maxConcurrentTasks = 3;
        private int maxPeersPerTask = 50;
        private long downloadLimitBytesPerSecond;
        private long uploadLimitBytesPerSecond;
        private Executor listenerExecutor;
        private Function<byte[], PeerTransport> transportFactory = NioTransport::new;
        private PeerDiscoverySource peerDiscovery;

        @Override
        public Builder listenPort(int port) {
            this.listenPort = port;
            return this;
        }

        @Override
        public Builder maxConcurrentTasks(int max) {
            this.maxConcurrentTasks = max;
            return this;
        }

        @Override
        public Builder maxPeersPerTask(int max) {
            this.maxPeersPerTask = max;
            return this;
        }

        @Override
        public Builder downloadLimitBytesPerSecond(long bytesPerSecond) {
            this.downloadLimitBytesPerSecond = bytesPerSecond;
            return this;
        }

        @Override
        public Builder uploadLimitBytesPerSecond(long bytesPerSecond) {
            this.uploadLimitBytesPerSecond = bytesPerSecond;
            return this;
        }

        /**
         * 注入自定义监听器回调线程；缺省为库内单线程守护线程。
         */
        @Override
        public Builder listenerExecutor(Executor executor) {
            this.listenerExecutor = executor;
            return this;
        }

        /**
         * 传输实现选择（api 级选项）；缺省 NIO（ADR-0003 生产路径），
         * BLOCKING 为阻塞参照实现（差分/调试）。
         */
        @Override
        public Builder transport(TorrentClient.Transport transport) {
            this.transportFactory = transport == TorrentClient.Transport.NIO
                    ? NioTransport::new
                    : BlockingTransport::new;
            return this;
        }

        /**
         * 注入传输工厂（差分验收/调试用，绕过 api 级 {@link #transport} 枚举直接
         * 指定实现）；缺省为 NIO 事件循环。
         */
        public Builder transportFactory(Function<byte[], PeerTransport> factory) {
            this.transportFactory = factory;
            return this;
        }

        /**
         * 注入去中心化 Peer 发现源（如 javathunder-dht 的 DhtPeerDiscovery）；
         * 生命周期归本 client：close 时一并关闭。未注入则仅 tracker 发现。
         */
        @Override
        public Builder peerDiscovery(PeerDiscoverySource source) {
            this.peerDiscovery = source;
            return this;
        }

        @Override
        public DefaultTorrentClient build() throws IOException {
            return new DefaultTorrentClient(this);
        }
    }

    private final int maxPeersPerTask;
    private final Semaphore slots;
    private final Executor eventExecutor;
    private final ExecutorService ownedExecutor;
    private final TrackerClient trackerClient = new TrackerClient();
    @Nullable
    private final UdpTrackerClient udpTracker;
    private final RateLimiter globalDownload;
    private final RateLimiter globalUpload;
    private final byte[] peerId = PeerIds.generate();
    private final PeerTransport transport;
    @Nullable
    private final PeerDiscoverySource peerDiscovery;
    private final ConcurrentHashMap<String, DownloadSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private DefaultTorrentClient(Builder builder) throws IOException {
        this.maxPeersPerTask = builder.maxPeersPerTask;
        this.slots = new Semaphore(Math.max(1, builder.maxConcurrentTasks));
        this.peerDiscovery = builder.peerDiscovery;
        UdpTrackerClient udp = null;
        try {
            udp = new UdpTrackerClient();
        } catch (IOException e) {
            // UDP 不可用（无网络栈等极端环境）：引擎自动回退 HTTP tracker
        }
        this.udpTracker = udp;
        this.globalDownload = builder.downloadLimitBytesPerSecond <= 0
                ? RateLimiter.unlimited()
                : new RateLimiter(builder.downloadLimitBytesPerSecond);
        this.globalUpload = builder.uploadLimitBytesPerSecond <= 0
                ? RateLimiter.unlimited()
                : new RateLimiter(builder.uploadLimitBytesPerSecond);
        if (builder.listenerExecutor != null) {
            this.ownedExecutor = null;
            this.eventExecutor = builder.listenerExecutor;
        } else {
            this.ownedExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "javathunder-events");
                thread.setDaemon(true);
                return thread;
            });
            this.eventExecutor = ownedExecutor;
        }
        this.transport = builder.transportFactory.apply(peerId);
        this.transport.listen(builder.listenPort, this::routeByInfoHash);
    }

    @Nullable
    private TransportHandler routeByInfoHash(byte[] infoHash) {
        DownloadSession session = sessions.get(HexFormat.of().formatHex(infoHash));
        return session == null ? null : session.transportHandler();
    }

    @Override
    public DownloadTask download(Path torrentFile, DownloadOptions options) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("client is closed");
        }
        TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(torrentFile));
        return startSession(meta, options);
    }

    /**
     * 导入已有文件直接做种（G2）：见 {@link io.github.oatelauser.thunder.api.TorrentClient#seed}。
     * 实现要点：DownloadOptions 以 seedAfterComplete=true 构造（SEEDING 态的
     * pause/announce 语义正确），DownloadSession.startSeedOnly() 做校验与落位。
     */
    @Override
    public DownloadTask seed(Path torrentFile, SeedOptions seedOptions)
            throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("client is closed");
        }
        TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(torrentFile));
        DownloadOptions options = new DownloadOptions(
            seedOptions.dataDir(), true, true, /*seedAfterComplete=*/ true,
            0, seedOptions.uploadLimitBytesPerSecond(), RestartVerifyMode.FULL);
        return startSession(meta, options, /*seedOnly=*/ true);
    }

    /**
     * 磁力链接下载（B1）：先经 BEP 9 拉取 info 字典并自校验 info-hash，
     * 再交给正常下载会话。返回的 future 在元数据就绪前不完成——快照在此之前
     * 反映 metadata 状态（fraction=0）。
     */
    @Override
    public DownloadTask download(MagnetUri magnet, DownloadOptions options) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("client is closed");
        }
        slots.acquire();
        boolean[] released = { false };
        Runnable releaseOnce = () -> {
            if (!released[0]) {
                released[0] = true;
                slots.release();
            }
        };
        try {
            MetadataFetcher fetcher = new MetadataFetcher(magnet.infoHash(), magnet.trackers(),
                    transport, trackerClient, transport.listeningPort(), peerDiscovery, udpTracker);
            return new MagnetDownloadTask(
                    fetcher.fetch().thenApply(infoBytes -> {
                        try {
                            return TorrentMetadata.fromInfoDict(infoBytes, magnet.trackers());
                        } catch (RuntimeException e) {
                            throw new IllegalStateException("fetched metadata invalid", e);
                        }
                    }), magnet, options, this::startSessionFromMagnet, releaseOnce, eventExecutor);
        } catch (RuntimeException e) {
            releaseOnce.run();
            throw e;
        }
    }

    private DownloadTask startSessionFromMagnet(TorrentMetadata meta, DownloadOptions options) {
        try {
            return startSession(meta, options);
        } catch (Exception e) {
            throw new IllegalStateException("cannot start magnet download session", e);
        }
    }

    private DownloadTask startSession(TorrentMetadata meta, DownloadOptions options) throws Exception {
        return startSession(meta, options, false);
    }

    private DownloadTask startSession(TorrentMetadata meta, DownloadOptions options, boolean seedOnly)
            throws Exception {
        slots.acquire();
        DownloadSession session;
        try {
            session = new DownloadSession(meta, options,
                    new DownloadSession.SessionConfig(maxPeersPerTask, transport.listeningPort(),
                            globalDownload, globalUpload, peerDiscovery, udpTracker),
                    transport, trackerClient, eventExecutor, peerId, seedOnly);
        } catch (IOException | RuntimeException e) {
            slots.release();
            throw e;
        }
        String key = HexFormat.of().formatHex(meta.infoHash());
        sessions.put(key, session);
        DownloadTaskImpl task = new DownloadTaskImpl(session, () -> {
            sessions.remove(key, session);
            slots.release();
        });
        if (seedOnly) {
            session.startSeedOnly();
        } else {
            session.start();
        }
        return task;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (DownloadSession session : sessions.values()) {
            try {
                session.cancel(false);
            } catch (RuntimeException ignored) {
            }
        }
        if (peerDiscovery != null) {
            peerDiscovery.close();
        }
        if (udpTracker != null) {
            udpTracker.close();
        }
        transport.close();
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }
}
