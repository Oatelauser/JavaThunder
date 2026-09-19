package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadOrder;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.FilePriority;
import io.github.oatelauser.thunder.api.PeerDiscoverySource;
import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.ratelimit.RateLimiter;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import io.github.oatelauser.thunder.core.internal.storage.MultiFileStorage;
import io.github.oatelauser.thunder.core.internal.storage.ResumeState;
import io.github.oatelauser.thunder.core.internal.storage.ResumeStore;
import io.github.oatelauser.thunder.core.internal.storage.StorageManager;
import io.github.oatelauser.thunder.core.internal.storage.TorrentStorage;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerClient;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerEvent;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerGateway;
import io.github.oatelauser.thunder.core.internal.tracker.UdpTrackerClient;
import io.github.oatelauser.thunder.core.internal.webseed.HttpRangeClient;
import io.github.oatelauser.thunder.core.internal.wire.BitfieldMessage;
import io.github.oatelauser.thunder.core.internal.wire.Cancel;
import io.github.oatelauser.thunder.core.internal.wire.Choke;
import io.github.oatelauser.thunder.core.internal.wire.ExtendedMessage;
import io.github.oatelauser.thunder.core.internal.wire.HashReject;
import io.github.oatelauser.thunder.core.internal.wire.HashRequest;
import io.github.oatelauser.thunder.core.internal.wire.Hashes;
import io.github.oatelauser.thunder.core.internal.wire.Have;
import io.github.oatelauser.thunder.core.internal.wire.HaveAll;
import io.github.oatelauser.thunder.core.internal.wire.HaveNone;
import io.github.oatelauser.thunder.core.internal.wire.Interested;
import io.github.oatelauser.thunder.core.internal.wire.KeepAlive;
import io.github.oatelauser.thunder.core.internal.wire.NotInterested;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import io.github.oatelauser.thunder.core.internal.wire.PieceMessage;
import io.github.oatelauser.thunder.core.internal.wire.RejectRequest;
import io.github.oatelauser.thunder.core.internal.wire.Request;
import io.github.oatelauser.thunder.core.internal.wire.SuggestPiece;
import io.github.oatelauser.thunder.core.internal.wire.AllowedFast;
import io.github.oatelauser.thunder.core.internal.wire.Unchoke;
import io.github.oatelauser.thunder.core.internal.wire.UnsupportedMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单下载会话的编排根（推送模型，ADR-0003）：连接与消息由 {@link PeerTransport} 回调驱动，
 * 磁盘写与校验在虚拟线程 worker 上执行（Selector 线程零阻塞的前提）。持有生命周期
 * 状态机、连接管理、消息分发、piece 流水线协调与上传服务。
 *
 * <p>协作者：{@link TaskEventDispatcher}——监听事件扇出（固定事件执行器，单监听器
 * 异常不扩散）；{@link TrackerAnnouncer}——announce 的 tier 失败转移与全败指数退避；
 * {@link TrackerGateway}——announce URL 的 HTTP/UDP 路由 seam；{@link PexManager}——
 * BEP 10/11 对等交换（BEP 27 私有种子禁用）；{@link ResumeStore}——断点持久化与
 * 降频刷盘；{@link PieceVerifier}——齐件 SHA-1 校验与落盘；{@link SessionStats}——
 * 计数与 EMA 速率；{@link PieceScheduler}——远端位图单源 / 在途表 / 生产选件；
 * {@link ChokingManager}——tit-for-tat unchoke 决策。
 */
public final class DownloadSession {

    private static final Logger log = LoggerFactory.getLogger(DownloadSession.class);

    private static final int PIPELINE_DEPTH = 32;
    private static final int MAX_BAD_PIECES_PER_PEER = 2;
    /** 组装器并发件的内存预算与硬上限（maxActivePieces = min(预算/件长, maxPeers, 硬上限)）。 */
    private static final long ASSEMBLER_MEMORY_BUDGET = 64L * 1024 * 1024;
    private static final int MAX_CONCURRENT_PIECES = 64;

    public record SessionConfig(int maxPeers, int listenPort,
                                RateLimiter globalDownload, RateLimiter globalUpload,
                                @Nullable
                                PeerDiscoverySource discovery,
                                @Nullable UdpTrackerClient udpTracker) {
    }

    private final TorrentMetadata meta;
    private final DownloadOptions options;
    private final SessionConfig config;
    /**
     * 任务级限速（两级串联：全局桶 ∧ 任务桶都需放行；null = 任务直通只走全局）。
     */
    private final RateLimiter taskDownloadLimit;
    private final RateLimiter taskUploadLimit;
    private final PeerTransport transport;
    private final byte[] peerId;
    private final TorrentStorage storage;
    private final Path resumeFile;
    private final PieceScheduler scheduler;
    private final ChokingManager choking;
    private final Random random = new Random();
    private final long startedAtMillis = System.currentTimeMillis();
    private final ExecutorService blockWorkers = Executors.newVirtualThreadPerTaskExecutor();

    private final Bitfield local;
    /** 选择性下载投影：必需件位图 + 进度/完成分母（无过滤器 = 全量，行为不变）。 */
    private final WantedPieces wanted;
    /** 顺序下载（DownloadOrder.SEQUENTIAL）：选件按索引升序，流式消费场景。 */
    private final boolean sequential;
    private final CompletableFuture<DownloadResult> future = new CompletableFuture<>();
    private final TaskEventDispatcher dispatcher;
    private final SessionStats stats;
    private final ResumeStore store;
    private final TrackerGateway gateway;
    private final TrackerAnnouncer announcer;
    private final PexManager pex;
    /** HTTP 兜底源通道（BEP 19，url-list 为空则不存在）；与 Peer 通道平行、互为备份。 */
    @Nullable
    private final WebSeedFetcher webSeed;
    private final ConcurrentHashMap<String, PeerSession> peers = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<InetSocketAddress> candidates = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Integer> badPiecesByPeer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, PieceAssembler> assemblers = new ConcurrentHashMap<>();
    /**
     * Peer 通道的在途件登记：选件器不排除其中的件（bestBusy 补块路径允许其他会话
     * 合件收尾），持有语义归首个抢占会话。占用互斥见 {@link #claimPieceForPeer}：
     * 与 verifyingPieces 是一对分工集合——Peer 抢占走本集合，WebSeed 认领与齐件
     * 待校验走 verifyingPieces（对选件器完全隐藏）。
     */
    private final Set<Integer> activePieces = ConcurrentHashMap.newKeySet();
    /**
     * 隐藏带（对选件器完全排除）：两处写入——Peer 齐件待校验（本地位图落定前不可
     * 再被选中，防重复请求→丢弃→饥饿）与 WebSeed 的独占认领位（见
     * {@link #claimPieceForWebSeed}）。与 activePieces 的跨集合互斥由两侧对称核对
     * 保证：Peer 占位后核对本集合让位，WebSeed 认领后核对 activePieces 让位。
     */
    private final Set<Integer> verifyingPieces = ConcurrentHashMap.newKeySet();
    /**
     * 组装器内存上限：min(64MB/pieceLength, maxPeers, 64) 个并发件。
     */
    private final int maxActivePieces;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile TaskState state = TaskState.QUEUED;
    /**
     * pause() 前的活跃态（DOWNLOADING/SEEDING）：resume() 回原态——做种暂停后恢复仍是做种。
     */
    private TaskState pausedFrom;

    public DownloadSession(TorrentMetadata meta, DownloadOptions options,
            SessionConfig config, PeerTransport transport, TrackerClient trackerClient,
            Executor eventExecutor, byte[] peerId) throws IOException {
        this(meta, options, config, transport, trackerClient, eventExecutor, peerId, false);
    }

    /**
     * @param seedOnly G2 纯做种会话：存储以"导入已有数据"模式打开（数据在最终名即工作对象），
     *                 启动走 {@link #startSeedOnly()} 而非 {@link #start()}。
     */
    public DownloadSession(TorrentMetadata meta, DownloadOptions options,
            SessionConfig config, PeerTransport transport, TrackerClient trackerClient,
            Executor eventExecutor, byte[] peerId, boolean seedOnly) throws IOException {
        this.meta = meta;
        this.options = options;
        this.config = config;
        this.transport = transport;
        this.peerId = peerId.clone();
        this.storage = meta.multiFile()
                ? new MultiFileStorage(meta, options.targetDir(), seedOnly)
                : new StorageManager(meta, options.targetDir(), seedOnly);
        this.resumeFile = storage.partFile().resolveSibling(meta.name() + ".jt-resume");
        this.local = new Bitfield(meta.pieceCount());
        // 选择性下载与文件优先级投影（seedOnly 恒全量 NORMAL：做种必须完整持有）
        this.wanted = new WantedPieces(meta,
                seedOnly ? path -> true : options.fileFilter(),
                seedOnly ? path -> FilePriority.NORMAL : options.filePriorities());
        this.sequential = options.downloadOrder() == DownloadOrder.SEQUENTIAL;
        this.scheduler = new PieceScheduler(meta.pieceCount(), meta.pieceLength(), meta.length());
        this.choking = new ChokingManager(random);
        this.maxActivePieces = Math.max(1, (int) Math.min(Math.min(config.maxPeers(),
                MAX_CONCURRENT_PIECES), ASSEMBLER_MEMORY_BUDGET / Math.max(1, meta.pieceLength())));
        this.taskDownloadLimit = options.downloadLimitBytesPerSecond() > 0
                ? new RateLimiter(options.downloadLimitBytesPerSecond()) : null;
        this.taskUploadLimit = options.uploadLimitBytesPerSecond() > 0
                ? new RateLimiter(options.uploadLimitBytesPerSecond()) : null;
        this.stats = new SessionStats();
        this.dispatcher = new TaskEventDispatcher(new CopyOnWriteArrayList<>(), eventExecutor);
        this.store = new ResumeStore(resumeFile, meta, stats::uploaded, stats::downloaded);
        this.gateway = new TrackerGateway(trackerClient, config.udpTracker());
        this.announcer = new TrackerAnnouncer(meta.infoHash(), peerId, config.listenPort(),
                meta.trackerTiers(), gateway, dispatcher,
                this::remainingWantedBytes,
                this::offerCandidate, stats::uploaded, stats::downloaded);
        this.pex = new PexManager(meta, config.maxPeers());
        this.webSeed = meta.webSeeds().isEmpty()
                ? null
                : new WebSeedFetcher(meta, new HttpRangeClient(meta.webSeeds(), meta.length()), this);
    }

    // ---------------------------------------------------------------- lifecycle

    public synchronized void start() {
        if (state != TaskState.QUEUED) {
            throw new IllegalStateException("session already started");
        }
        running.set(true);
        restoreResume();
        setState(TaskState.DOWNLOADING);
        spawnLoops();
        startWebSeed();
    }

    /**
     * 纯做种启动（G2）：对 targetDir 下已有数据全量校验，全部通过则直接进入
     * SEEDING——不经历 DOWNLOADING，也不要求 .part/.jt-resume 存在（种子校验
     * 通过即事实上的完成态）。任一件校验失败即 FAILED（数据不完整不该做种，
     * 调用方应走 download 让引擎补缺）。
     */
    public synchronized void startSeedOnly() {
        if (state != TaskState.QUEUED) {
            throw new IllegalStateException("session already started");
        }
        setState(TaskState.VERIFYING);
        for (int i = 0; i < meta.pieceCount(); i++) {
            boolean verified;
            try {
                verified = storage.verifyPiece(i);
            } catch (IOException e) {
                fail(e);
                return;
            }
            if (!verified) {
                fail(new IllegalStateException("seed-only: piece " + i
                        + " failed verification — data incomplete or corrupt, use download() instead"));
                return;
            }
            localSet(i);
        }
        // 导入数据已在校验通过的位置（最终名/目录树），无需落位；也不能调
        // storage.finish()——它会关闭全部通道，SEEDING 的上传服务将永久
        // ClosedChannelException（complete() 的 seed 分支同样跳过 finish 以保住通道）。
        running.set(true);
        setState(TaskState.SEEDING);
        announcer.announce(TrackerEvent.STARTED);
        spawnLoops();
    }

    private void spawnLoops() {
        Thread.ofVirtual().name("javathunder-tracker").start(wrap(this::trackerLoop));
        Thread.ofVirtual().name("javathunder-connect").start(wrap(this::connectLoop));
        Thread.ofVirtual().name("javathunder-choking").start(wrap(this::chokingLoop));
        Thread.ofVirtual().name("javathunder-progress").start(wrap(this::progressLoop));
    }

    private Runnable wrap(Runnable loop) {
        return () -> {
            try {
                loop.run();
            } catch (Throwable t) {
                fail(t);
            }
        };
    }

    private void startWebSeed() {
        if (webSeed != null) {
            webSeed.start();
        }
    }

    private void stopWebSeed() {
        if (webSeed != null) {
            webSeed.stop();
        }
    }

    public synchronized void pause() {
        if (state != TaskState.DOWNLOADING && state != TaskState.SEEDING) {
            return;
        }
        pausedFrom = state;
        running.set(false);
        stopWebSeed();
        closeAllPeers();
        announcer.announce(TrackerEvent.STOPPED);
        saveResumeQuietly();
        setState(TaskState.PAUSED);
    }

    public synchronized void resume() {
        if (state != TaskState.PAUSED) {
            return;
        }
        running.set(true);
        // 回到暂停前的活跃态：SEEDING（seed-only / seedAfterComplete）与 DOWNLOADING 语义不同
        setState(pausedFrom != null ? pausedFrom : TaskState.DOWNLOADING);
        spawnLoops();
        startWebSeed();
    }

    public synchronized void cancel(boolean deleteData) {
        if (state == TaskState.CANCELLED || state == TaskState.COMPLETED) {
            return;
        }
        running.set(false);
        stopWebSeed();
        closeAllPeers();
        announcer.announce(TrackerEvent.STOPPED);
        try {
            storage.close();
        } catch (IOException ignored) {
        }
        blockWorkers.shutdownNow();
        if (deleteData) {
            try {
                Files.deleteIfExists(storage.partFile());
            } catch (IOException ignored) {
            }
            store.delete();
        } else {
            saveResumeQuietly();
        }
        setState(TaskState.CANCELLED);
        future.cancel(true);
    }

    private void fail(Throwable error) {
        if (future.isDone()) {
            // 已终态（完成/取消后后台循环的收尾异常，如事件线程池关闭后的拒绝）：
            // 不回写状态、不重复扇出——状态机不允许从终态回退
            log.debug("post-terminal loop exception: {}", error.toString());
            return;
        }
        running.set(false);
        stopWebSeed();
        closeAllPeers();
        try {
            storage.close();
        } catch (IOException ignored) {
        }
        log.error("task {} failed", meta.name(), error);
        dispatcher.error(error);
        setState(TaskState.FAILED);
        future.completeExceptionally(error);
    }

    // ---------------------------------------------------------------- resume

    private void restoreResume() {
        if (!options.resumeEnabled() || !Files.exists(resumeFile)) {
            return;
        }
        ResumeState resume = store.load();
        if (resume == null) {
            return; // 状态文件不可用：从零开始（原因已在 store 内记录）
        }
        try {
            stats.addDownloaded(resume.downloaded());
            stats.addUploaded(resume.uploaded());
            if (resume.completed().cardinality() > 0) {
                setState(TaskState.VERIFYING);
                Set<Integer> sampled = store.restartSample(resume.completed());
                for (int i = 0; i < meta.pieceCount(); i++) {
                    if (!resume.completed().has(i)) {
                        continue;
                    }
                    boolean mustVerify = switch (options.restartVerifyMode()) {
                        case FULL -> true;
                        case SAMPLED -> sampled.contains(i); // ~10% 抽样 + 首/末件必查
                        case NONE -> false;
                    };
                    if (!mustVerify || storage.verifyPiece(i)) {
                        localSet(i);
                    } else {
                        log.info("resume: piece {} failed re-verification, will re-download", i);
                        storage.clearPiece(i);
                    }
                }
            }
            log.info("resume: {}/{} pieces trusted (mode={})", localCardinality(),
                    meta.pieceCount(), options.restartVerifyMode());
        } catch (IOException e) {
            log.warn("resume verification I/O failure, starting fresh", e);
        }
    }

    private void saveResumeQuietly() {
        store.saveNow(resumeSnapshot());
    }

    private Bitfield resumeSnapshot() {
        synchronized (local) {
            return Bitfield.fromBytes(local.toBytes(), meta.pieceCount());
        }
    }

    // ---------------------------------------------------------------- tracker

    private void trackerLoop() {
        announcer.announce(TrackerEvent.STARTED);
        while (running.get()) {
            if (sleepMillis(Math.max(2, announcer.intervalSeconds()) * 1000L)) {
                return;
            }
            if (running.get()) {
                announcer.announce(TrackerEvent.NONE);
                replenishFromDiscovery(); // tracker 之外：DHT 等来源周期补充候选
            }
        }
    }

    /**
     * 去中心化来源补充：结果异步入候选队列，空结果不惊动。
     */
    private void replenishFromDiscovery() {
        PeerDiscoverySource discovery = config.discovery();
        if (discovery == null || !running.get()) {
            return;
        }
        if (meta.privateFlag()) {
            return; // BEP 27：private 种子禁止 DHT 等非 tracker 通道发现 peer
        }
        discovery.getPeers(meta.infoHash()).whenComplete((peers, error) -> {
            if (error == null) {
                for (InetSocketAddress peer : peers) {
                    offerCandidate(peer);
                }
            }
        });
    }

    /**
     * 候选入队：过滤自连回声（tracker/PEX/DHT 把我们自己回给我们）与已连接会话。
     */
    private void offerCandidate(InetSocketAddress address) {
        if (PeerAddresses.isSelfConnection(address, config.listenPort())) {
            return; // 我们自己
        }
        String key = PeerAddresses.key(address);
        if (!peers.containsKey(key)) {
            candidates.offer(address);
        }
    }

    // ---------------------------------------------------------------- peers

    private void connectLoop() {
        while (running.get()) {
            InetSocketAddress address = candidates.poll();
            if (address == null) {
                if (sleepMillis(200)) {
                    return;
                }
                continue;
            }
            if (peers.size() >= config.maxPeers() || peers.containsKey(PeerAddresses.key(address))) {
                continue;
            }
            transport.connect(address, meta.infoHash(), transportHandler);
        }
    }

    private final TransportHandler transportHandler = new TransportHandler() {
        @Override
        public void onConnected(PeerChannel channel) {
            DownloadSession.this.onConnected(channel);
        }

        @Override
        public void onConnectFailed(InetSocketAddress address, Throwable cause) {
            log.debug("connect to {} failed: {}", address, cause.toString());
        }
    };

    /**
     * 出站/入站连接就绪（transport 在其连接线程上回调）。
     */
    private void onConnected(PeerChannel channel) {
        if (!running.get() || state != TaskState.DOWNLOADING && state != TaskState.SEEDING) {
            channel.close();
            return;
        }
        String key = PeerAddresses.key(channel.remoteAddress());
        if (rejectDuplicateLink(channel, key)) {
            return;
        }
        PeerSession session = new PeerSession(key, channel);
        if (!registerSession(session, channel)) {
            return;
        }
        sendInitialHandshake(session, channel);
        log.debug("peer {} connected", key);
        dispatcher.peerConnected(key);
    }

    /**
     * 重复链路丢弃：同 host:port 的第二条连接直接关；再按 BEP 3 peer-id 去重。
     * 返回 true 表示该连接已被关闭拒绝。
     */
    private boolean rejectDuplicateLink(PeerChannel channel, String key) {
        if (peers.containsKey(key)) {
            channel.close();
            return true;
        }
        // BEP 3 以 peer id 为身份：同一客户端的第二条链路（入站 + 出站同时建立，或按监听地址
        // 反复重连）一律丢弃。否则对端（如 ttorrent 按 host-id 去重）在关闭重复链路前，
        // 其分片簿记已被重复会话扰乱，出现无效分片（A1 互操作实测）。
        for (PeerSession existing : peers.values()) {
            if (Arrays.equals(existing.channel.remotePeerId(), channel.remotePeerId())) {
                channel.close();
                return true;
            }
        }
        return false;
    }

    /**
     * 注册会话：挂载消息 / 关闭监听，并向调度器登记空位图。与并发注册竞态时关闭
     * 新连接并返回 false。
     */
    private boolean registerSession(PeerSession session, PeerChannel channel) {
        PeerSession existing = peers.putIfAbsent(session.key, session);
        if (existing != null) {
            channel.close();
            return false;
        }
        channel.setMessageListener(messages -> {
            for (PeerWireMessage message : messages) {
                handleMessage(session, message);
            }
        });
        channel.setCloseListener(cause -> peerClosed(session));
        // BEP 3：bitfield 是可选消息（无数据的客户端常直接省略，只用 have 逐片通告）。
        // 连接即注册空位图，否则 have-only 对端的分片永远不可见、调度器不会发出任何请求。
        scheduler.peerConnected(session.key, new Bitfield(meta.pieceCount()));
        return true;
    }

    /**
     * 初始通告：本地位图 + interested + BEP 10 PEX 协商。BEP 6 快速扩展协商成功时
     * 用 HaveAll/HaveNone 替代整幅位图（大种子的位图可达数十 KB，单帧 5 字节替代）。
     */
    private void sendInitialHandshake(PeerSession session, PeerChannel channel) {
        if (channel.remoteSupportsFast()) {
            if (localAllSet()) {
                channel.write(HaveAll.INSTANCE);
            } else if (localCardinality() == 0) {
                channel.write(HaveNone.INSTANCE);
            } else {
                channel.write(new BitfieldMessage(localBytes()));
            }
        } else if (localCardinality() > 0) {
            channel.write(new BitfieldMessage(localBytes()));
        }
        channel.write(Interested.INSTANCE);
        // BEP 10/11：声明 ut_pex（子 ID 2）——对端支持即协商 PEX。
        // BEP 27：private 种子禁用 PEX（不做对等交换，仅 tracker 通道）。
        if (channel.remoteSupportsExtensions()) {
            ExtendedMessage handshake = pex.ourHandshake();
            if (handshake != null) {
                channel.write(handshake);
            }
        }
    }

    private void peerClosed(PeerSession session) {
        peers.remove(session.key);
        scheduler.peerDisconnected(session.key);
        session.serveExecutor.shutdownNow();
        synchronized (session) {
            releaseAssignment(session);
            for (BlockRequest block : session.issued) {
                scheduler.clearInFlight(block);
            }
        }
        log.debug("peer {} disconnected", session.key);
        dispatcher.peerDisconnected(session.key, null);
    }

    private void handleMessage(PeerSession session, PeerWireMessage message) {
        switch (message) {
            case Choke c -> {
                session.peerChokingUs = true;
                synchronized (session) {
                    releaseAssignment(session);
                }
            }
            case Unchoke u -> {
                session.peerChokingUs = false;
                requestRefillAsync(session);
            }
            case Interested i -> session.remoteInterested = true;
            case NotInterested n -> session.remoteInterested = false;
            case Have h -> {
                scheduler.peerHave(session.key, h.pieceIndex());
                requestRefillAsync(session);
            }
            case BitfieldMessage b -> scheduler.peerConnected(session.key,
                    Bitfield.fromBytes(b.bits(), meta.pieceCount()));
            case Request r -> serveUpload(session, r);
            case PieceMessage p -> blockWorkers.execute(() -> processBlock(session, p));
            case SuggestPiece s -> {
                // BEP 6：对端的选件建议不采纳——本引擎有自己的稀缺度调度策略
            }
            case HaveAll h -> scheduler.peerConnected(session.key, Bitfield.allSet(meta.pieceCount()));
            case HaveNone h -> {
            }
            case RejectRequest r -> {
                BlockRequest rejected = new BlockRequest(r.pieceIndex(), r.begin(), r.length());
                session.issued.remove(rejected);
                scheduler.clearInFlight(rejected); // 允许重新请求
            }
            case AllowedFast a -> {
                // BEP 6：choke 豁免清单不使用——我们不向被 choke 的对端请求
            }
            case ExtendedMessage e -> handleExtendedMessage(session, e);
            case HashRequest r -> session.channel.write(HashExchange.respond(meta, r));
            case Hashes h -> { // 下载会话不发哈希请求（PieceLayerFetcher 负责）：迟到应答忽略
            }
            case HashReject h -> {
            }
            case UnsupportedMessage u -> {
            }
            case Cancel c -> {
            }
            case KeepAlive k -> {
            }
        }
    }

    /** BEP 10 扩展消息分发：子 ID 0 = 扩展握手、UT_PEX_ID = ut_pex；其余不消费。 */
    private void handleExtendedMessage(PeerSession session, ExtendedMessage message) {
        if (message.extendedId() == 0) {
            pex.onRemoteHandshake(session, message.payload());
        } else if (message.extendedId() == PexManager.UT_PEX_ID) {
            pex.onPex(session, message.payload(), peers, this::offerCandidate);
        }
    }

    /**
     * Have/Unchoke 后的异步补发：worker 线程内持 session 监视器重填请求管线。
     */
    private void requestRefillAsync(PeerSession session) {
        if (state == TaskState.DOWNLOADING) {
            blockWorkers.execute(() -> {
                synchronized (session) {
                    refillRequests(session);
                }
            });
        }
    }

    private void refillRequests(PeerSession session) {
        ArrayList<PeerWireMessage> batch = new ArrayList<>();
        while (session.issued.size() + batch.size() < PIPELINE_DEPTH) {
            if (session.pending.isEmpty()) {
                if (session.currentPiece >= 0) {
                    break; // 当前 piece 的 block 已全部发出，等待响应
                }
                int piece = sequential
                        ? scheduler.pickSequentialFor(session.key, local, wanted::priorityOf,
                                new PieceConstraints(activePieces, verifyingPieces,
                                        assemblers.size(), maxActivePieces, this::hasMissingBlock))
                        : scheduler.pickFor(session.key, local, wanted::priorityOf,
                                new PieceConstraints(activePieces, verifyingPieces,
                                        assemblers.size(), maxActivePieces, this::hasMissingBlock));
                if (piece < 0) {
                    break;
                }
                if (!claimPieceForPeer(piece)) {
                    // 选件快照与占位间隙被并发方（另一 refill / WebSeed 认领）抢先：
                    // 放弃该件重选。终止性见 claimPieceForPeer——非自旋，占满时 pick
                    // 返回 -1 自然退出，循环边界（PIPELINE_DEPTH）不受影响。
                    continue;
                }
                session.currentPiece = piece;
                PieceAssembler assembler = assemblers.get(piece);
                for (BlockRequest block : scheduler.blocksOf(piece)) {
                    boolean alreadyReceived = assembler != null && assembler.received.contains(block);
                    if (!alreadyReceived && !scheduler.isInFlight(block)) {
                        session.pending.addLast(block);
                    }
                }
                if (session.pending.isEmpty()) {
                    activePieces.remove(piece);
                    session.currentPiece = -1;
                    break; // 该 piece 的块已全部在途（其他 Peer 处理中）
                }
            }
            BlockRequest block = session.pending.pollFirst();
            if (block == null) {
                break;
            }
            batch.add(new Request(block.pieceIndex(), block.begin(), block.length()));
            scheduler.markInFlight(block);
            session.issued.add(block);
        }
        if (!batch.isEmpty()) {
            session.channel.write(batch); // 单缓冲一次刷出
        }
    }

    /**
     * Peer 侧条件占位（与 WebSeed 的 {@link #claimPieceForWebSeed} 对称的原子闭环）：
     * 选件器对两个占用集合只有遍历瞬间的弱一致视图，选件与占位之间存在 TOCTOU
     * 间隙——本方法把自由件的占位收敛为并发集合 add 的布尔返回（单键原子），
     * 失败即并发方先到，调用方放弃该件重选。
     *
     * <p>跨集合互斥：占位成功后核对 verifyingPieces——WebSeed 认领（先抢
     * verifying 再核对 activePieces 让位）可能在选件与占位的间隙抢先同件，查见
     * 即撤回让给 WebSeed。两侧对称核对合起来覆盖全部交错序：任一件至多一个
     * 抢占者（两侧都退让的交错只是本轮漏选，重选自愈，不违反互斥）。
     *
     * <p>在途件（选件器 bestBusy 补块路径返回的已占件）不抢占、直接加入合件：
     * 占位仍归先到会话，块级去重由在途表与组装器收块集兜底。
     *
     * <p>重选终止性：占用失败意味着该件已在并发集合中可见，而选件器遍历读的正是
     * 同一对 live 集合——重选要么跳过它、要么以补块语义合件成功；每次失败都由
     * 其他线程的真实状态迁移引发（件数有限），不构成自旋。
     *
     * @return true = 占位成功（或补块合件），可指派该件；false = 并发方先到，放弃重选
     */
    boolean claimPieceForPeer(int piece) {
        if (activePieces.contains(piece)) {
            return true; // 补块路径：在途件多会话合件，占位归属先到者
        }
        if (!activePieces.add(piece)) {
            return false; // 自由件被并发 refill 抢先
        }
        if (verifyingPieces.contains(piece)) {
            activePieces.remove(piece);
            return false; // WebSeed 在选件与占位间隙认领：让给 WebSeed
        }
        return true;
    }

    private boolean hasMissingBlock(int piece) {
        if (!wanted.requiredPiece(piece)) {
            return false; // 选择性下载：不想要的件永远"无缺失块"，选件器跳过
        }
        PieceAssembler assembler = assemblers.get(piece);
        if (assembler == null) {
            return true;
        }
        for (BlockRequest block : scheduler.blocksOf(piece)) {
            if (!assembler.received.contains(block)) {
                return true;
            }
        }
        return false;
    }

    private void releaseAssignment(PeerSession session) {
        session.pending.clear();
        // 在途标记必须一并清掉：被 Choke 后这些块的对端响应可能永不到达，
        // 不清则所在 Piece 停滞到断连为止（D1 实验中暴露的既有隐患）
        for (BlockRequest block : session.issued) {
            scheduler.clearInFlight(block);
        }
        session.issued.clear();
        if (session.currentPiece >= 0) {
            activePieces.remove(session.currentPiece);
            session.currentPiece = -1;
        }
    }

    /**
     * 块到达：不限速时在选择器线程内联组装（仅 memcpy 级成本，零派发）；
     * 限速或齐件后落盘在 worker 虚拟线程上（事件循环零阻塞）。
     */
    private void processBlock(PeerSession session, PieceMessage message) {
        BlockRequest block = new BlockRequest(message.pieceIndex(), message.begin(), message.block().length);
        boolean downloadUnlimited = config.globalDownload().isUnlimited() && taskDownloadLimit == null;
        if (downloadUnlimited) {
            handleBlock(session, message, block);
        } else {
            blockWorkers.execute(() -> {
                try {
                    config.globalDownload().acquire(message.block().length);
                    if (taskDownloadLimit != null) {
                        taskDownloadLimit.acquire(message.block().length);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                handleBlock(session, message, block);
            });
        }
    }

    private void handleBlock(PeerSession session, PieceMessage message, BlockRequest block) {
        synchronized (session) {
            int piece = message.pieceIndex();
            if (piece < 0 || piece >= meta.pieceCount()) {
                return;
            }
            PieceAssembler assembler = assemblers.get(piece);
            if (localHas(piece) || assembler != null && assembler.received.contains(block)) {
                session.issued.remove(block);
                scheduler.clearInFlight(block);
                return; // 重复投递
            }
            if (assembler == null) {
                PieceAssembler created = new PieceAssembler(storage.pieceLengthOf(piece),
                        scheduler.blocksOf(piece));
                PieceAssembler raced = assemblers.putIfAbsent(piece, created);
                assembler = raced != null ? raced : created;
            }
            int slot = assembler.slotOf(message.begin(), message.block().length);
            if (slot < 0) {
                session.issued.remove(block);
                scheduler.clearInFlight(block);
                return; // 未请求过的块（恶意/迟到），忽略
            }
            assembler.blocks[slot] = message.block(); // 零拷贝：块引用即组装
            assembler.received.add(block);
            session.issued.remove(block);
            scheduler.clearInFlight(block);
            stats.addDownloaded(message.block().length);
            choking.recordReceived(session.key, message.block().length);
            if (assembler.received.size() == assembler.expectedBlocks) {
                assemblers.remove(piece, assembler);
                activePieces.remove(piece);
                verifyingPieces.add(piece); // 本地位图落定前不可再被选中
                releaseAssignment(session);
                refillRequests(session); // 立即指派下一件——不等齐件校验/落盘（worker 并行做）
                PieceAssembler done = assembler;
                blockWorkers.execute(() -> finishPiece(done, piece, session));
            } else if (session.issued.size() < PIPELINE_DEPTH / 2) {
                refillRequests(session); // 低水位补发，避免逐块进入监视器
            }
        }
    }

    /**
     * 齐件：哈希与落盘在监视器外（不阻塞该 Peer 后续块的事件循环处理），状态变更短暂持锁。
     */
    private void finishPiece(PieceAssembler assembler, int piece, PeerSession source) {
        synchronized (source) {
            if (localHas(piece)) {
                verifyingPieces.remove(piece);
                return; // 已被其他路径完成
            }
        }
        // —— 监视器外：CPU/磁盘重活，可与该 Peer 的后续块并行 ——
        boolean verified;
        try {
            verified = verifyAndStoreByVersion(assembler, piece);
        } catch (IOException e) {
            verifyingPieces.remove(piece);
            fail(e);
            return;
        }
        // —— 监视器内：状态变更与下一件指派 ——
        synchronized (source) {
            if (verified) {
                onVerifiedPiece(source, piece);
            } else {
                onBadPiece(source, piece);
            }
        }
    }

    /** 按 torrent 版本选择校验器（v2 走 Merkle 层带，v1/hybrid 走 SHA-1）。 */
    private boolean verifyAndStoreByVersion(PieceAssembler assembler, int piece) throws IOException {
        return switch (meta.version()) {
            case V2 -> V2PieceVerifier.verifyAndStore(storage, meta, assembler, piece);
            default -> PieceVerifier.verifyAndStore(storage, meta, assembler, piece);
        };
    }

    /**
     * 校验通过的状态迁移（须在 source 监视器内调用）。
     */
    private void onVerifiedPiece(PeerSession source, int piece) {
        if (localHas(piece)) {
            return; // 与并行路径竞争，对方已落定
        }
        completeVerifiedPiece(piece);
        if (state == TaskState.DOWNLOADING) {
            refillRequests(source);
        }
    }

    /**
     * 件校验通过后的落定——Peer 与 WebSeed 两条完成路径的公共尾部：位图落定、
 * 断点脏标记、事件扇出与 Have 广播，全部收齐触发 complete。
     */
    private void completeVerifiedPiece(int piece) {
        localSet(piece);
        verifyingPieces.remove(piece); // 好件：本地位图已覆盖，隐藏使命结束
        store.markDirty(); // 降频：progressLoop 定时刷盘，不再每件一写
        dispatcher.pieceComplete(piece);
        broadcast(new Have(piece));
        log.debug("piece {}/{} verified", piece + 1, meta.pieceCount());
        if (localAllSet()) {
            complete();
        }
    }

    /**
     * 坏件处置（须在 source 监视器内调用）：从未落盘，丢弃组装器即可重下，无清盘成本。
     */
    private void onBadPiece(PeerSession source, int piece) {
        verifyingPieces.remove(piece); // 坏件：允许重选重下
        int bad = badPiecesByPeer.merge(source.key, 1, Integer::sum);
        log.warn("piece {} failed verification from {} (bad #{})", piece, source.key, bad);
        if (bad >= MAX_BAD_PIECES_PER_PEER) {
            source.channel.close(); // 恶意/损坏数据源
        }
        refillRequests(source);
    }

    private void complete() {
        boolean seed = options.seedAfterComplete();
        announcer.announce(TrackerEvent.COMPLETED);
        if (!seed) {
            try {
                storage.finish();
            } catch (IOException e) {
                fail(e);
                return;
            }
        }
        try {
            Files.deleteIfExists(resumeFile);
        } catch (IOException ignored) {
        }
        setState(seed ? TaskState.SEEDING : TaskState.COMPLETED);
        Path file = seed ? storage.partFile() : storage.finalFile();
        future.complete(new DownloadResult(meta.name(), file, wanted.wantedBytes(),
                Duration.ofMillis(System.currentTimeMillis() - startedAtMillis)));
        if (!seed) {
            running.set(false);
            closeAllPeers();
        }
    }

    private void serveUpload(PeerSession session, Request request) {
        if (session.weChokingThem || !localHas(request.pieceIndex())) {
            // BEP 6：对协商了快速扩展的对端显式拒绝（而非沉默），让对端立即回收在途槽位
            if (session.channel.remoteSupportsFast()) {
                session.channel.write(new RejectRequest(
                        request.pieceIndex(), request.begin(), request.length()));
            }
            return;
        }
        session.serveExecutor.execute(() -> {
            try {
                byte[] block = storage.readBlock(request.pieceIndex(), request.begin(), request.length());
                config.globalUpload().acquire(block.length);
                if (taskUploadLimit != null) {
                    taskUploadLimit.acquire(block.length);
                }
                session.channel.write(
                        new PieceMessage(request.pieceIndex(), request.begin(), block));
                stats.addUploaded(block.length);
                choking.recordSent(session.key, block.length);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalArgumentException e) {
                log.debug("cannot serve block to {}: {}", session.key, e.toString());
            }
        });
    }

    private void broadcast(PeerWireMessage message) {
        for (PeerSession session : peers.values()) {
            session.channel.write(message);
        }
    }

    private void closeAllPeers() {
        for (PeerSession session : peers.values()) {
            session.channel.close();
        }
        peers.clear();
    }

    // ---------------------------------------------------------------- choking / progress

    private void chokingLoop() {
        int tick = 0;
        while (running.get()) {
            if (sleepMillis(10_000)) {
                return;
            }
            if (state != TaskState.DOWNLOADING && state != TaskState.SEEDING) {
                continue;
            }
            Set<Object> connected = new HashSet<>(peers.keySet());
            Set<Object> interested = new HashSet<>();
            for (PeerSession session : peers.values()) {
                if (session.remoteInterested) {
                    interested.add(session.key);
                }
            }
            Set<Object> unchoked = tick > 0 && tick % 3 == 0
                    ? choking.rotateOptimistic(connected, interested)
                    : choking.recompute(connected, interested);
            for (PeerSession session : peers.values()) {
                boolean shouldUnchoke = unchoked.contains(session.key);
                if (session.weChokingThem == shouldUnchoke) {
                    session.weChokingThem = !shouldUnchoke;
                    session.channel.write(shouldUnchoke ? Unchoke.INSTANCE : Choke.INSTANCE);
                }
            }
            tick++;
            if (tick % 6 == 0) { // choking 每 10s 一拍：PEX 每 60s 一轮
                pex.broadcast(peers);
            }
        }
    }

    private void progressLoop() {
        stats.tickRates(System.currentTimeMillis()); // 采样基线（等价原循环入口的 last* 初始化）
        while (running.get()) {
            if (sleepMillis(500)) {
                saveResumeQuietly(); // 退出前兜底刷
                return;
            }
            long now = System.currentTimeMillis();
            store.flushIfDue(now, this::resumeSnapshot);
            stats.tickRates(now);
            dispatcher.progress(snapshot());
        }
    }

    // ---------------------------------------------------------------- accessors

    public CompletableFuture<DownloadResult> future() {
        return future;
    }

    public TaskState state() {
        return state;
    }

    public ProgressSnapshot snapshot() {
        // 字节级进度（已校验件 + 在途已收块）：块一到进度就动，不等整片校验——
        // 大件种子按"完成片数"计会在首片完成前长时间显示 0%，观测上不可接受。
        // 分母按选择性下载的必需字节（无过滤器时等于总长，行为不变）
        long basis = wanted.wantedBytes();
        double fraction = basis == 0 ? 1.0
                : Math.min(1.0, (double) downloadedRemainingBasis() / basis);
        long remaining = Math.max(0, basis - downloadedRemainingBasis());
        Long eta = stats.downloadRate() > 0 && remaining > 0
                ? remaining * 1000 / stats.downloadRate() : null;
        return new ProgressSnapshot(fraction, stats.downloaded(), stats.uploaded(),
                stats.downloadRate(), stats.uploadRate(), peers.size(), availability(), eta);
    }

    /**
     * 已完成字节数（ETA 基数）：已落件 + 在途组装块，末件按实际长度截断
     * （上限钳到必需字节——resume 残留的非必需件不计入进度）。
     */
    private long downloadedRemainingBasis() {
        long verifiedBytes = 0;
        int lastPiece = meta.pieceCount() - 1;
        for (int i = 0; i < meta.pieceCount(); i++) {
            if (localHas(i)) {
                verifiedBytes += (i == lastPiece)
                        ? meta.length() - (long) lastPiece * meta.pieceLength()
                        : meta.pieceLength();
            }
        }
        long inFlightBytes = 0;
        for (PieceAssembler assembler : assemblers.values()) {
            inFlightBytes += (long) assembler.received.size() * PieceScheduler.BLOCK_SIZE;
        }
        return Math.min(verifiedBytes + inFlightBytes, wanted.wantedBytes());
    }

    public void addListener(TaskListener listener) {
        dispatcher.add(listener);
    }

    public Path partFile() {
        return storage.partFile();
    }

    /**
     * 供 DefaultTorrentClient 出站连接与入站路由使用。
     */
    public TransportHandler transportHandler() {
        return transportHandler;
    }

    /**
     * 全片段最小持有者数（本地位图 + 调度器远端位图单源）。
     */
    private double availability() {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < meta.pieceCount(); i++) {
            int count = localHas(i) ? 1 : 0;
            for (Bitfield remote : scheduler.remotes()) {
                if (remote.has(i)) {
                    count++;
                }
            }
            min = Math.min(min, count);
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private void setState(TaskState to) {
        TaskState from = state;
        state = to;
        dispatcher.stateChanged(from, to);
    }

    // ---------------------------------------------------------------- web seed 挂点（WebSeedFetcher 经此与会话协作）

    /** WebSeed 通道是否应继续拉取（下载态且会话运行中；SEEDING/暂停/完成即停）。 */
    boolean webSeedActive() {
        return running.get() && state == TaskState.DOWNLOADING;
    }

    /** 该件是否已校验落定。 */
    boolean hasPiece(int piece) {
        return localHas(piece);
    }

    /** 该件是否已被任一通道占用（Peer 组装/在途 或 校验中）。 */
    boolean pieceClaimed(int piece) {
        return verifyingPieces.contains(piece) || activePieces.contains(piece);
    }

    /**
     * WebSeed 认领一件（原子占位，防两通道重复拉同件）：先 verifyingPieces.add 抢占
     * （布尔返回即占位结果，false = 另一 WebSeed 循环先到），再核对 Peer 通道未在
     * 检查与占位的间隙选中同件（activePieces）——若是则撤回自己的占位让给 Peer。
     *
     * <p>配对约束：认领成功（true）后，成功路径经 verifyAndStoreWebSeedPiece →
     * completeVerifiedPiece 释放；失败/坏件路径必须调 {@link #releaseWebSeedClaim}
     * 释放——只允许释放自己成功认领过的件。认领失败方必须直接让出，不得 release
     * （会放掉占用方的认领，导致双通道重复拉取）。
     */
    boolean claimPieceForWebSeed(int piece) {
        if (!verifyingPieces.add(piece)) {
            return false; // 已被占用（另一 WebSeed 循环先到）
        }
        if (activePieces.contains(piece)) {
            verifyingPieces.remove(piece);
            return false; // Peer 通道刚选中同件：让给 Peer
        }
        return true;
    }

    /** WebSeed 放弃认领（拉取失败/坏件），交还 Peer 通道。 */
    void releaseWebSeedClaim(int piece) {
        verifyingPieces.remove(piece);
    }

    /** 该件的 Swarm 持有数（选件排序用）。 */
    int pieceAvailability(int piece) {
        return scheduler.availability(piece);
    }

    /** WebSeed 字节计入两级限速（与 Peer 通道共享同一对令牌桶）与下载计数。 */
    void webSeedDownloaded(int bytes) throws InterruptedException {
        config.globalDownload().acquire(bytes);
        if (taskDownloadLimit != null) {
            taskDownloadLimit.acquire(bytes);
        }
        stats.addDownloaded(bytes);
    }

    /**
     * WebSeed 整件校验并落盘：按 16KiB 块拆分组装（复用既有校验/落盘路径），
     * 通过则走公共落定尾部。
     *
     * @return false = 哈希不符（坏件，从未落盘）；IOException = 存储故障（应失败任务）
     */
    boolean verifyAndStoreWebSeedPiece(int piece, byte[] body) throws IOException {
        List<BlockRequest> blocks = scheduler.blocksOf(piece);
        PieceAssembler assembler = new PieceAssembler(storage.pieceLengthOf(piece), blocks);
        int slot = 0;
        for (BlockRequest block : blocks) {
            assembler.blocks[slot++] =
                    Arrays.copyOfRange(body, block.begin(), block.begin() + block.length());
        }
        boolean verified = verifyAndStoreByVersion(assembler, piece);
        if (verified) {
            completeVerifiedPiece(piece);
        }
        return verified;
    }

    /** WebSeed 通道遭遇不可恢复故障时委托会话失败整个任务。 */
    void failTask(Throwable error) {
        fail(error);
    }

    private boolean localHas(int index) {
        synchronized (local) {
            return local.has(index);
        }
    }

    private void localSet(int index) {
        synchronized (local) {
            local.set(index);
        }
    }

    private int localCardinality() {
        synchronized (local) {
            return local.cardinality();
        }
    }

    private boolean localAllSet() {
        // 完成语义按必需件集（选择性下载）；resume 里残留的非必需位不干扰判定
        synchronized (local) {
            return wanted.completeAgainst(local);
        }
    }

    /** 选择性下载门控（WebSeed 通道与 Peer 通道共用语义，见 WantedPieces）。 */
    boolean wantedPiece(int piece) {
        return wanted.requiredPiece(piece);
    }

    /** 件优先级（文件优先级投影，见 WantedPieces；0 = 不需要）。 */
    int piecePriority(int piece) {
        return wanted.priorityOf(piece);
    }

    /** 顺序下载模式（WebSeed 通道选件与 Peer 通道同序，见 DownloadOrder）。 */
    boolean sequentialDownload() {
        return sequential;
    }

    private byte[] localBytes() {
        synchronized (local) {
            return local.toBytes();
        }
    }

    /**
     * tracker left（剩余量）：与进度分母同口径按必需件计——必需件中已持有的字节
     * （末件按实际长度截断）从 wantedBytes 里扣除。不用 localCardinality()*pieceLength：
     * resume 残留的非必需位会把 left 冲小，tracker 侧统计失真。
     */
    private long remainingWantedBytes() {
        long remaining = wanted.wantedBytes();
        for (int i = 0; i < meta.pieceCount(); i++) {
            if (wanted.priorityOf(i) > 0 && localHas(i)) {
                remaining -= Math.min(meta.pieceLength(), meta.length() - (long) i * meta.pieceLength());
            }
        }
        return Math.max(0, remaining);
    }

    private static boolean sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
