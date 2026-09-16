package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.*;
import io.github.oatelauser.thunder.core.internal.bencode.*;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.ratelimit.RateLimiter;
import io.github.oatelauser.thunder.core.internal.storage.*;
import io.github.oatelauser.thunder.core.internal.tracker.*;
import io.github.oatelauser.thunder.core.internal.wire.*;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个种子的下载会话（推送模型，ADR-0003）：连接与消息由 {@link PeerTransport} 回调驱动，
 * 磁盘写与校验在虚拟线程 worker 上执行（Selector 线程零阻塞的前提）。
 */
public final class DownloadSession {

    private static final Logger log = LoggerFactory.getLogger(DownloadSession.class);

    private static final int PIPELINE_DEPTH = 32;
    private static final int MAX_BAD_PIECES_PER_PEER = 2;
    /**
     * BEP 11：我们侧 ut_pex 子 ID（对端用协商值发给我们，我们统一用 2）。
     */
    private static final int UT_PEX_ID = 2;

    public record SessionConfig(int maxPeers, int listenPort,
                                RateLimiter globalDownload, RateLimiter globalUpload,
                                @Nullable
                                PeerDiscoverySource discovery,
                                @Nullable UdpTrackerClient udpTracker) {

        @Deprecated
        public SessionConfig(int maxPeers, int listenPort, RateLimiter globalDownload,
                RateLimiter globalUpload) {
            this(maxPeers, listenPort, globalDownload, globalUpload, null, null);
        }

        @Deprecated
        public SessionConfig(int maxPeers, int listenPort, RateLimiter globalDownload,
                RateLimiter globalUpload, @Nullable PeerDiscoverySource discovery) {
            this(maxPeers, listenPort, globalDownload, globalUpload, discovery, null);
        }
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
    private final TrackerClient trackerClient;
    private final byte[] peerId;
    private final Executor eventExecutor;
    private final TorrentStorage storage;
    private final Path resumeFile;
    private final PieceScheduler scheduler;
    private final ChokingManager choking;
    private final Random random = new Random();
    private final long startedAtMillis = System.currentTimeMillis();
    private final ExecutorService blockWorkers = Executors.newVirtualThreadPerTaskExecutor();

    private final Bitfield local;
    private final CompletableFuture<DownloadResult> future = new CompletableFuture<>();
    private final CopyOnWriteArrayList<TaskListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, PeerSession> peers = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<InetSocketAddress> candidates = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Integer> badPiecesByPeer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, PieceAssembler> assemblers = new ConcurrentHashMap<>();
    private final Set<Integer> activePieces = ConcurrentHashMap.newKeySet();
    /**
     * 齐件待校验：从选中器隐藏，防止本地位图落定前被重新选中（重复请求→丢弃→饥饿）。
     */
    private final Set<Integer> verifyingPieces = ConcurrentHashMap.newKeySet();
    /**
     * 组装器内存上限：min(64MB/pieceLength, maxPeers, 64) 个并发件。
     */
    private final int maxActivePieces;
    private final AtomicLong downloaded = new AtomicLong();
    private final AtomicLong uploaded = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * resume 降频：脏标记 + progressLoop ≤2s 刷一次（原每件一写，512 件/128MB → 数量级减少）。
     */
    private final AtomicBoolean resumeDirty = new AtomicBoolean(false);

    private volatile TaskState state = TaskState.QUEUED;
    /** pause() 前的活跃态（DOWNLOADING/SEEDING）：resume() 回原态——做种暂停后恢复仍是做种。 */
    private TaskState pausedFrom;
    private volatile int announceIntervalSeconds = 5;
    /**
     * 全部 tracker 失败后的指数退避基数（秒）；成功 announce 复位为 0。
     */
    private volatile int announceBackoffSeconds;
    private volatile long downloadRate;
    private volatile long uploadRate;

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
        this.trackerClient = trackerClient;
        this.eventExecutor = eventExecutor;
        this.peerId = peerId.clone();
        this.storage = meta.multiFile()
                ? new MultiFileStorage(meta, options.targetDir(), seedOnly)
                : new StorageManager(meta, options.targetDir(), seedOnly);
        this.resumeFile = storage.partFile().resolveSibling(meta.name() + ".jt-resume");
        this.local = new Bitfield(meta.pieceCount());
        this.scheduler = new PieceScheduler(meta.pieceCount(), meta.pieceLength(), meta.length(), random);
        this.choking = new ChokingManager(random);
        this.maxActivePieces = Math.max(1, (int) Math.min(Math.min(config.maxPeers(), 64),
                64L * 1024 * 1024 / Math.max(1, meta.pieceLength())));
        this.taskDownloadLimit = options.downloadLimitBytesPerSecond() > 0
                ? new RateLimiter(options.downloadLimitBytesPerSecond()) : null;
        this.taskUploadLimit = options.uploadLimitBytesPerSecond() > 0
                ? new RateLimiter(options.uploadLimitBytesPerSecond()) : null;
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
        announce(TrackerEvent.STARTED);
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

    public synchronized void pause() {
        if (state != TaskState.DOWNLOADING && state != TaskState.SEEDING) {
            return;
        }
        pausedFrom = state;
        running.set(false);
        closeAllPeers();
        announce(TrackerEvent.STOPPED);
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
    }

    public synchronized void cancel(boolean deleteData) {
        if (state == TaskState.CANCELLED || state == TaskState.COMPLETED) {
            return;
        }
        running.set(false);
        closeAllPeers();
        announce(TrackerEvent.STOPPED);
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
            ResumeState.delete(resumeFile);
        } else {
            saveResumeQuietly();
        }
        setState(TaskState.CANCELLED);
        future.cancel(true);
    }

    private void fail(Throwable error) {
        running.set(false);
        closeAllPeers();
        try {
            storage.close();
        } catch (IOException ignored) {
        }
        log.error("task {} failed", meta.name(), error);
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onError(error);
                } catch (RuntimeException ignored) {
                }
            });
        }
        setState(TaskState.FAILED);
        future.completeExceptionally(error);
    }

    // ---------------------------------------------------------------- resume

    private void restoreResume() {
        if (!options.resumeEnabled() || !Files.exists(resumeFile)) {
            return;
        }
        try {
            ResumeState resume = ResumeState.load(resumeFile, meta.infoHash(), meta.pieceCount());
            downloaded.set(resume.downloaded());
            uploaded.set(resume.uploaded());
            if (resume.completed().cardinality() > 0) {
                setState(TaskState.VERIFYING);
                java.util.Set<Integer> sampled = pickRestartSample(resume.completed());
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
        } catch (ResumeException e) {
            log.info("resume state unusable, starting fresh: {}", e.getMessage());
        } catch (IOException e) {
            log.warn("resume verification I/O failure, starting fresh", e);
        }
    }

    /**
     * SAMPLED 档的抽样子集：均匀随机 10% + 边界件（首/末）。
     */
    private java.util.Set<Integer> pickRestartSample(Bitfield completed) {
        java.util.Set<Integer> sample = new java.util.HashSet<>();
        int count = meta.pieceCount();
        if (count > 0 && completed.has(0)) {
            sample.add(0);
        }
        if (count > 1 && completed.has(count - 1)) {
            sample.add(count - 1);
        }
        // 上限取 min(已完成件数, 10%)：只完成少量件就恢复时（如 5/1000），
        // 若按总件数定 target，样本永远凑不齐 → 死循环
        // 上限取 min(已完成件数, 10%)：只完成少量件就恢复时（如 5/1000），
        // 若按总件数定 target，样本永远凑不齐 → 死循环
        int target = Math.max(1, Math.min(completed.cardinality(), count / 10));
        while (sample.size() < target) {
            int candidate = random.nextInt(count);
            if (completed.has(candidate)) {
                sample.add(candidate);
            }
        }
        return sample;
    }

    private void saveResumeQuietly() {
        try {
            Bitfield snapshot;
            synchronized (local) {
                snapshot = Bitfield.fromBytes(local.toBytes(), meta.pieceCount());
            }
            ResumeState.save(resumeFile, new ResumeState(meta.infoHash(), meta.pieceCount(),
                    snapshot, uploaded.get(), downloaded.get(), System.currentTimeMillis()));
        } catch (IOException e) {
            log.warn("cannot save resume state", e);
        }
    }

    // ---------------------------------------------------------------- tracker

    private void trackerLoop() {
        announce(TrackerEvent.STARTED);
        while (running.get()) {
            if (sleepMillis(Math.max(2, announceIntervalSeconds) * 1000L)) {
                return;
            }
            if (running.get()) {
                announce(TrackerEvent.NONE);
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
                for (java.net.InetSocketAddress peer : peers) {
                    offerCandidate(peer);
                }
            }
        });
    }

    private void announce(TrackerEvent event) {
        long left = Math.max(0, meta.length() - localCardinality() * meta.pieceLength());
        AnnounceRequest request = new AnnounceRequest(meta.infoHash(), peerId, config.listenPort(),
                uploaded.get(), downloaded.get(), left, event, 50);
        boolean anySuccess = false;
        for (List<String> tier : meta.trackerTiers()) {
            for (String url : tier) {
                try {
                    var response = UdpTrackerClient
                            .supports(url) && config.udpTracker() != null
                            ? config.udpTracker().announce(url, request)
                            : trackerClient.announce(url, request);
                    if (response.failureReason() != null) {
                        log.warn("tracker {} rejected announce: {}", url, response.failureReason());
                        fireTrackerAnnounce(url, response.failureReason(), 0, 0);
                        continue;
                    }
                    announceIntervalSeconds = response.interval();
                    for (InetSocketAddress peer : response.peers()) {
                        offerCandidate(peer);
                    }
                    fireTrackerAnnounce(url, null, response.seeders(), response.leechers());
                    anySuccess = true;
                    announceBackoffSeconds = 0; // 成功即复位
                    return;
                } catch (TrackerException e) {
                    log.debug("tracker {} failed: {}", url, e.getMessage());
                    fireTrackerAnnounce(url, e.getMessage(), 0, 0);
                }
            }
        }
        log.warn("announce {} failed on all trackers", event);
        if (!anySuccess) {
            // 全部 tracker 失败：指数退避 interval×2^k，上限 30 分钟
            announceBackoffSeconds = announceBackoffSeconds == 0
                    ? Math.max(2, announceIntervalSeconds) * 2
                    : Math.min(announceBackoffSeconds * 2, 30 * 60);
            announceIntervalSeconds = announceBackoffSeconds;
        }
    }

    private void fireTrackerAnnounce(String url, @Nullable String failure,
            int seeders, int leechers) {
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onTrackerAnnounce(url, failure, seeders, leechers);
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    private void offerCandidate(InetSocketAddress address) {
        if (address.getPort() == config.listenPort()
                && (address.getAddress().isLoopbackAddress() || address.getAddress().isAnyLocalAddress())) {
            return; // 我们自己
        }
        String key = key(address);
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
            if (peers.size() >= config.maxPeers() || peers.containsKey(key(address))) {
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
        String key = key(channel.remoteAddress());
        if (peers.containsKey(key)) {
            channel.close();
            return;
        }
        // BEP 3 以 peer id 为身份：同一客户端的第二条链路（入站 + 出站同时建立，或按监听地址
        // 反复重连）一律丢弃。否则对端（如 ttorrent 按 host-id 去重）在关闭重复链路前，
        // 其分片簿记已被重复会话扰乱，出现无效分片（A1 互操作实测）。
        for (PeerSession existing : peers.values()) {
            if (Arrays.equals(existing.channel.remotePeerId(), channel.remotePeerId())) {
                channel.close();
                return;
            }
        }
        PeerSession session = new PeerSession(key, channel, meta.pieceCount());
        PeerSession existing = peers.putIfAbsent(key, session);
        if (existing != null) {
            channel.close();
            return;
        }
        channel.setMessageListener(messages -> {
            for (PeerWireMessage message : messages) {
                handleMessage(session, message);
            }
        });
        channel.setCloseListener(cause -> peerClosed(session));
        // BEP 3：bitfield 是可选消息（无数据的客户端常直接省略，只用 have 逐片通告）。
        // 连接即注册空位图，否则 have-only 对端的分片永远不可见、调度器不会发出任何请求。
        scheduler.peerConnected(session.key, session.remote);
        if (localCardinality() > 0) {
            channel.write(new BitfieldMessage(localBytes()));
        }
        channel.write(Interested.INSTANCE);
        // BEP 10/11：声明 ut_pex（子 ID 2）——对端支持即协商 PEX。
        // BEP 27：private 种子禁用 PEX（不做对等交换，仅 tracker 通道）。
        if (channel.remoteSupportsExtensions() && !meta.privateFlag()) {
            Map<BString, BencodeValue> m = new TreeMap<>(BString.UNSIGNED_ORDER);
            m.put(BString.of("ut_pex"), new BInteger(UT_PEX_ID));
            Map<BString, BencodeValue> handshake = new TreeMap<>(BString.UNSIGNED_ORDER);
            handshake.put(BString.of("m"), new BDict(m));
            channel.write(new ExtendedMessage(0, Bencode.encode(new BDict(handshake))));
        }
        log.debug("peer {} connected", key);
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onPeerConnected(key);
                } catch (RuntimeException ignored) {
                }
            });
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
        for (TaskListener listener : listeners) {
            String address = session.key;
            eventExecutor.execute(() -> {
                try {
                    listener.onPeerDisconnected(address, null);
                } catch (RuntimeException ignored) {
                }
            });
        }
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
                if (state == TaskState.DOWNLOADING) {
                    blockWorkers.execute(() -> {
                        synchronized (session) {
                            refillRequests(session);
                        }
                    });
                }
            }
            case Interested i -> session.remoteInterested = true;
            case NotInterested n -> session.remoteInterested = false;
            case Have h -> {
                scheduler.peerHave(session.key, h.pieceIndex());
                if (state == TaskState.DOWNLOADING) {
                    blockWorkers.execute(() -> {
                        synchronized (session) {
                            refillRequests(session);
                        }
                    });
                }
            }
            case BitfieldMessage b -> {
                session.remote = Bitfield.fromBytes(b.bits(), meta.pieceCount());
                scheduler.peerConnected(session.key, session.remote);
            }
            case Request r -> serveUpload(session, r);
            case PieceMessage p -> blockWorkers.execute(() -> processBlock(session, p));
            case HaveAll h -> {
                Bitfield all = new Bitfield(meta.pieceCount());
                for (int i = 0; i < meta.pieceCount(); i++) {
                    all.set(i);
                }
                session.remote = all;
                scheduler.peerConnected(session.key, all);
            }
            case HaveNone h -> {
            }
            case RejectRequest r -> {
                BlockRequest rejected = new BlockRequest(r.pieceIndex(), r.begin(), r.length());
                session.issued.remove(rejected);
                scheduler.clearInFlight(rejected); // 允许重新请求
            }
            case io.github.oatelauser.thunder.core.internal.wire.ExtendedMessage e -> {
                if (e.extendedId() == 0) {
                    handleExtensionHandshake(session, e.payload());
                } else if (e.extendedId() == UT_PEX_ID) {
                    handlePex(session, e.payload());
                }
                // 其余扩展消息本会话不消费
            }
            case UnsupportedMessage u -> {
            }
            case Cancel c -> {
            }
            case KeepAlive k -> {
            }
        }
    }

    /**
     * BEP 10 扩展握手（对端 → 我们）：记录其 ut_pex 子 ID（有则开启 PEX 接收）。
     */
    private void handleExtensionHandshake(PeerSession session, byte[] payload) {
        try {
            var value = io.github.oatelauser.thunder.core.internal.bencode.Bencode.decodeValue(
                    java.nio.ByteBuffer.wrap(payload));
            if (value instanceof io.github.oatelauser.thunder.core.internal.bencode.BDict dict
                    && dict.get("m") instanceof io.github.oatelauser.thunder.core.internal.bencode.BDict m
                    && m.get("ut_pex") instanceof io.github.oatelauser.thunder.core.internal.bencode.BInteger id) {
                session.remotePexId = (int) id.value();
                if (session.remotePexId > 0) {
                    log.debug("pex negotiated with {} (remote ut_pex id {})", session.key,
                            session.remotePexId);
                }
            }
        } catch (RuntimeException e) {
            log.debug("peer {} sent unreadable extension handshake", session.key);
        }
    }

    /**
     * BEP 11 ut_pex：解析 added 紧凑表并入候选队列。
     */
    private void handlePex(PeerSession session, byte[] payload) {
        if (meta.privateFlag()) {
            return; // BEP 27：private 种子不参与对等交换（我们也不广播，此处双保险）
        }
        try {
            var value = io.github.oatelauser.thunder.core.internal.bencode.Bencode.decodeValue(
                    java.nio.ByteBuffer.wrap(payload));
            if (!(value instanceof io.github.oatelauser.thunder.core.internal.bencode.BDict dict)
                    || !(dict.get("added") instanceof io.github.oatelauser.thunder.core.internal.bencode.BString added)) {
                return;
            }
            byte[] data = added.value();
            if (data.length % 6 != 0) {
                return;
            }
            int introduced = 0;
            for (int i = 0; i + 6 <= data.length; i += 6) {
                // added.f 为可选字段（BEP 11），即使缺失也照常取地址；位图语义（加密等）我们不用
                if (peers.size() >= config.maxPeers()) {
                    break;
                }
                String host = (data[i] & 0xFF) + "." + (data[i + 1] & 0xFF) + "."
                        + (data[i + 2] & 0xFF) + "." + (data[i + 3] & 0xFF);
                int port = ((data[i + 4] & 0xFF) << 8) | (data[i + 5] & 0xFF);
                offerCandidate(new java.net.InetSocketAddress(host, port));
                introduced++;
            }
            log.debug("pex from {}: {} candidates", session.key, introduced);
        } catch (RuntimeException e) {
            log.debug("peer {} sent unreadable pex", session.key);
        }
    }

    /**
     * BEP 11：向已协商 PEX 的对端周期广播当前连接表（added 紧凑表）。
     */
    private void broadcastPex() {
        if (meta.privateFlag()) {
            return; // BEP 27：private 种子不广播连接表
        }
        java.io.ByteArrayOutputStream compact = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream flags = new java.io.ByteArrayOutputStream();
        int count = 0;
        for (PeerSession session : peers.values()) {
            java.net.InetSocketAddress address = session.channel.remoteAddress();
            if (address.getAddress() == null) {
                continue;
            }
            byte[] ip = address.getAddress().getAddress();
            if (ip.length != 4) {
                continue; // IPv6 PEX 需 added6，暂不支持
            }
            compact.write(ip, 0, 4);
            compact.write(address.getPort() >> 8);
            compact.write(address.getPort() & 0xFF);
            flags.write(0x00); // 无特殊语义位
            count++;
        }
        if (count == 0) {
            return;
        }
        java.util.Map<io.github.oatelauser.thunder.core.internal.bencode.BString,
                io.github.oatelauser.thunder.core.internal.bencode.BencodeValue> dict =
                new java.util.TreeMap<>(io.github.oatelauser.thunder.core.internal.bencode.BString.UNSIGNED_ORDER);
        dict.put(io.github.oatelauser.thunder.core.internal.bencode.BString.of("added"),
                new io.github.oatelauser.thunder.core.internal.bencode.BString(compact.toByteArray()));
        dict.put(io.github.oatelauser.thunder.core.internal.bencode.BString.of("added.f"),
                new io.github.oatelauser.thunder.core.internal.bencode.BString(flags.toByteArray()));
        byte[] payload = io.github.oatelauser.thunder.core.internal.bencode.Bencode.encode(
                new io.github.oatelauser.thunder.core.internal.bencode.BDict(dict));
        int recipients = 0;
        for (PeerSession session : peers.values()) {
            if (session.remotePexId > 0) {
                try {
                    session.channel.write(
                            new io.github.oatelauser.thunder.core.internal.wire.ExtendedMessage(
                                    session.remotePexId, payload));
                    recipients++;
                } catch (RuntimeException ignored) {
                    // 通道关闭竞态：写失败由 close 路径收尾
                }
            }
        }
        log.debug("pex broadcast: {} peers listed, {} recipients", count, recipients);
    }

    private void refillRequests(PeerSession session) {
        java.util.ArrayList<PeerWireMessage> batch = new java.util.ArrayList<>();
        while (session.issued.size() + batch.size() < PIPELINE_DEPTH) {
            if (session.pending.isEmpty()) {
                if (session.currentPiece >= 0) {
                    break; // 当前 piece 的 block 已全部发出，等待响应
                }
                int piece = pickPieceFor(session);
                if (piece < 0) {
                    break;
                }
                session.currentPiece = piece;
                activePieces.add(piece);
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

    private int pickPieceFor(PeerSession session) {
        int bestFree = -1;
        int bestFreeAvailability = Integer.MAX_VALUE;
        int bestBusy = -1;
        int bestBusyAvailability = Integer.MAX_VALUE;
        for (int i = 0; i < meta.pieceCount(); i++) {
            if (localHas(i) || verifyingPieces.contains(i) || !session.remote.has(i) || !hasMissingBlock(i)) {
                continue;
            }
            int availability = scheduler.availability(i);
            if (!activePieces.contains(i)) {
                if (assemblers.size() >= maxActivePieces) {
                    continue; // 组装器满：不开新件（在途件仍可补块）
                }
                if (availability < bestFreeAvailability) {
                    bestFree = i;
                    bestFreeAvailability = availability;
                }
            } else if (availability < bestBusyAvailability) {
                bestBusy = i;
                bestBusyAvailability = availability;
            }
        }
        return bestFree >= 0 ? bestFree : bestBusy;
    }

    private boolean hasMissingBlock(int piece) {
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
            downloaded.addAndGet(message.block().length);
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
        byte[] hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (byte[] block : assembler.blocks) {
                digest.update(block);
            }
            hash = digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-1", e);
        }
        boolean verified;
        if (MessageDigest.isEqual(hash, meta.pieceHash(piece))) {
            try {
                ByteBuffer[] buffers = new ByteBuffer[assembler.blocks.length];
                for (int i = 0; i < buffers.length; i++) {
                    buffers[i] = ByteBuffer.wrap(assembler.blocks[i]);
                }
                storage.writePieceBuffers(piece, buffers);
                verified = true;
            } catch (IOException e) {
                verifyingPieces.remove(piece);
                fail(e);
                return;
            }
        } else {
            verified = false;
        }
        // —— 监视器内：状态变更与下一件指派 ——
        synchronized (source) {
            if (verified) {
                if (localHas(piece)) {
                    return; // 与并行路径竞争，对方已落定
                }
                localSet(piece);
                verifyingPieces.remove(piece); // 好件：本地位图已覆盖，隐藏使命结束
                resumeDirty.set(true); // 降频：progressLoop 定时刷盘，不再每件一写
                for (TaskListener listener : listeners) {
                    eventExecutor.execute(() -> {
                        try {
                            listener.onPieceComplete(piece);
                        } catch (RuntimeException ignored) {
                        }
                    });
                }
                broadcast(new Have(piece));
                log.debug("piece {}/{} verified", piece + 1, meta.pieceCount());
                if (localAllSet()) {
                    complete();
                    return;
                }
                refillRequests(source);
            } else {
                // 坏件从未落盘：丢弃组装器即可重下，无清盘成本
                verifyingPieces.remove(piece); // 坏件：允许重选重下
                int bad = badPiecesByPeer.merge(source.key, 1, Integer::sum);
                log.warn("piece {} failed verification from {} (bad #{})", piece, source.key, bad);
                if (bad >= MAX_BAD_PIECES_PER_PEER) {
                    source.channel.close(); // 恶意/损坏数据源
                }
                refillRequests(source);
            }
        }
    }

    private void complete() {
        boolean seed = options.seedAfterComplete();
        announce(TrackerEvent.COMPLETED);
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
        future.complete(new DownloadResult(meta.name(), file, meta.length(),
                Duration.ofMillis(System.currentTimeMillis() - startedAtMillis)));
        if (!seed) {
            running.set(false);
            closeAllPeers();
        }
    }

    private void serveUpload(PeerSession session, Request request) {
        if (session.weChokingThem || !localHas(request.pieceIndex())) {
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
                uploaded.addAndGet(block.length);
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
                broadcastPex();
            }
        }
    }

    private void progressLoop() {
        long lastDownloaded = downloaded.get();
        long lastUploaded = uploaded.get();
        long lastMillis = System.currentTimeMillis();
        long lastResumeFlush = lastMillis;
        while (running.get()) {
            if (sleepMillis(500)) {
                saveResumeQuietly(); // 退出前兜底刷
                return;
            }
            long now = System.currentTimeMillis();
            if (resumeDirty.get() && now - lastResumeFlush >= 2000) {
                resumeDirty.set(false);
                saveResumeQuietly();
                lastResumeFlush = now;
            }
            long dt = Math.max(1, now - lastMillis);
            // EMA 平滑（α=0.3）：瞬时抖动不至于让 ETA 上蹿下跳
            downloadRate = ema(downloadRate, (downloaded.get() - lastDownloaded) * 1000 / dt);
            uploadRate = ema(uploadRate, (uploaded.get() - lastUploaded) * 1000 / dt);
            lastDownloaded = downloaded.get();
            lastUploaded = uploaded.get();
            lastMillis = now;
            ProgressSnapshot snapshot = snapshot();
            for (TaskListener listener : listeners) {
                eventExecutor.execute(() -> {
                    try {
                        listener.onProgress(snapshot);
                    } catch (RuntimeException ignored) {
                    }
                });
            }
        }
    }

    private static long ema(long previous, long instantaneous) {
        return (long) (0.3 * instantaneous + 0.7 * Math.max(0, previous));
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
        // 大件种子按"完成片数"计会在首片完成前长时间显示 0%，观测上不可接受
        double fraction = meta.length() == 0 ? 1.0
            : Math.min(1.0, (double) downloadedRemainingBasis() / meta.length());
        long remaining = Math.max(0, meta.length() - downloadedRemainingBasis());
        Long eta = downloadRate > 0 && remaining > 0 ? remaining * 1000 / downloadRate : null;
        return new ProgressSnapshot(fraction, downloaded.get(), uploaded.get(),
                downloadRate, uploadRate, peers.size(), availability(), eta);
    }

    /**
     * 已完成字节数（ETA 基数）：已落件 + 在途组装块，末件按实际长度截断。
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
        return Math.min(verifiedBytes + inFlightBytes, meta.length());
    }

    public void addListener(TaskListener listener) {
        listeners.add(listener);
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

    private double availability() {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < meta.pieceCount(); i++) {
            int count = localHas(i) ? 1 : 0;
            for (PeerSession session : peers.values()) {
                if (session.remote.has(i)) {
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
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onStateChanged(from, to);
                } catch (RuntimeException ignored) {
                }
            });
        }
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
        synchronized (local) {
            return local.allSet();
        }
    }

    private byte[] localBytes() {
        synchronized (local) {
            return local.toBytes();
        }
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

    private static String key(InetSocketAddress address) {
        return (address.getAddress() != null ? address.getAddress().getHostAddress()
                : address.getHostString()) + ":" + address.getPort();
    }

    /**
     * 单个 Peer 的会话状态。pending/issued/currentPiece 由持有者线程在 session 监视器下访问。
     */
    private static final class PeerSession {
        final String key;
        final PeerChannel channel;
        final ArrayDeque<BlockRequest> pending = new ArrayDeque<>();
        final Set<BlockRequest> issued = ConcurrentHashMap.newKeySet();
        /**
         * 上传服务 FIFO（虚拟线程）：按请求到达顺序应答。BEP 3 不禁止乱序块，但请求序应答
         * 是主流实现事实标准，且 ttorrent 1.5 的 Piece.record 在收到 offset=0 的块时会重置
         * 整片缓冲——乱序块 0 会静默抹掉已收块导致校验失败（A1 互操作实测）。
         */
        final ExecutorService serveExecutor =
                Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        volatile Bitfield remote;
        volatile boolean peerChokingUs = true;
        volatile boolean weChokingThem = true;
        volatile boolean remoteInterested;
        /**
         * 对端协商的 ut_pex 子 ID（>0 表示 PEX 已协商，按此值发送）。
         */
        volatile int remotePexId = -1;
        int currentPiece = -1;

        PeerSession(String key, PeerChannel channel, int pieceCount) {
            this.key = key;
            this.channel = channel;
            this.remote = new Bitfield(pieceCount);
        }
    }

    /**
     * 在内存中按块槽位零拷贝组装：解码块直接挂引用，齐件后顺序喂摘要 + gather 落盘。
     */
    private static final class PieceAssembler {
        final byte[][] blocks;
        final int[] blockLengths;
        final int pieceLength;
        final Set<BlockRequest> received = ConcurrentHashMap.newKeySet();
        final int expectedBlocks;

        PieceAssembler(int pieceLength, List<BlockRequest> blocksOfPiece) {
            this.pieceLength = pieceLength;
            this.expectedBlocks = blocksOfPiece.size();
            this.blocks = new byte[expectedBlocks][];
            this.blockLengths = new int[expectedBlocks];
            for (BlockRequest block : blocksOfPiece) {
                int slot = block.begin() / PieceScheduler.BLOCK_SIZE;
                blockLengths[slot] = block.length();
            }
        }

        /**
         * 槽位校验：begin/length 必须与该槽的请求对齐（协议上对端只回我们请求过的块）。
         */
        int slotOf(int begin, int length) {
            if (begin % PieceScheduler.BLOCK_SIZE != 0) {
                return -1;
            }
            int slot = begin / PieceScheduler.BLOCK_SIZE;
            if (slot >= blockLengths.length || blockLengths[slot] != length) {
                return -1;
            }
            return slot;
        }
    }
}
