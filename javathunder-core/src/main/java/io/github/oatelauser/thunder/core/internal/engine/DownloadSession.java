package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.ratelimit.RateLimiter;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import io.github.oatelauser.thunder.core.internal.storage.ResumeException;
import io.github.oatelauser.thunder.core.internal.storage.ResumeState;
import io.github.oatelauser.thunder.core.internal.storage.StorageManager;
import io.github.oatelauser.thunder.core.internal.tracker.AnnounceRequest;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerClient;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerEvent;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerException;
import io.github.oatelauser.thunder.core.internal.wire.BitfieldMessage;
import io.github.oatelauser.thunder.core.internal.wire.Cancel;
import io.github.oatelauser.thunder.core.internal.wire.Choke;
import io.github.oatelauser.thunder.core.internal.wire.Handshake;
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
import io.github.oatelauser.thunder.core.internal.wire.Unchoke;
import io.github.oatelauser.thunder.core.internal.wire.UnsupportedMessage;
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
import java.util.ArrayDeque;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个种子的下载会话（推送模型，ADR-0003）：连接与消息由 {@link PeerTransport} 回调驱动，
 * 磁盘写与校验在虚拟线程 worker 上执行（Selector 线程零阻塞的前提）。
 */
public final class DownloadSession {

    private static final Logger log = LoggerFactory.getLogger(DownloadSession.class);
    private static final int PIPELINE_DEPTH = 32;
    private static final int MAX_BAD_PIECES_PER_PEER = 2;

    public record SessionConfig(int maxPeers, int listenPort,
                                RateLimiter globalDownload, RateLimiter globalUpload) {
    }

    private final TorrentMetadata meta;
    private final DownloadOptions options;
    private final SessionConfig config;
    /** 任务级限速（两级串联：全局桶 ∧ 任务桶都需放行；null = 任务直通只走全局）。 */
    private final RateLimiter taskDownloadLimit;
    private final RateLimiter taskUploadLimit;
    private final PeerTransport transport;
    private final TrackerClient trackerClient;
    private final byte[] peerId;
    private final Executor eventExecutor;
    private final StorageManager storage;
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
    /** 齐件待校验：从选中器隐藏，防止本地位图落定前被重新选中（重复请求→丢弃→饥饿）。 */
    private final Set<Integer> verifyingPieces = ConcurrentHashMap.newKeySet();
    /** 组装器内存上限：min(64MB/pieceLength, maxPeers, 64) 个并发件。 */
    private final int maxActivePieces;
    private final AtomicLong downloaded = new AtomicLong();
    private final AtomicLong uploaded = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** resume 降频：脏标记 + progressLoop ≤2s 刷一次（原每件一写，512 件/128MB → 数量级减少）。 */
    private final AtomicBoolean resumeDirty = new AtomicBoolean(false);

    private volatile TaskState state = TaskState.QUEUED;
    private volatile int announceIntervalSeconds = 5;
    /** 全部 tracker 失败后的指数退避基数（秒）；成功 announce 复位为 0。 */
    private volatile int announceBackoffSeconds;
    private volatile long downloadRate;
    private volatile long uploadRate;

    public DownloadSession(TorrentMetadata meta, DownloadOptions options, SessionConfig config,
                           PeerTransport transport, TrackerClient trackerClient,
                           Executor eventExecutor, byte[] peerId) throws IOException {
        this.meta = meta;
        this.options = options;
        this.config = config;
        this.transport = transport;
        this.trackerClient = trackerClient;
        this.eventExecutor = eventExecutor;
        this.peerId = peerId.clone();
        this.storage = new StorageManager(meta, options.targetDir());
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
        setState(TaskState.DOWNLOADING);
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
                for (int i = 0; i < meta.pieceCount(); i++) {
                    if (!resume.completed().has(i)) {
                        continue;
                    }
                    if (!options.verifyOnRestart() || storage.verifyPiece(i)) {
                        localSet(i);
                    } else {
                        log.info("resume: piece {} failed re-verification, will re-download", i);
                        storage.clearPiece(i);
                    }
                }
            }
            log.info("resume: {}/{} pieces trusted", localCardinality(), meta.pieceCount());
        } catch (ResumeException e) {
            log.info("resume state unusable, starting fresh: {}", e.getMessage());
        } catch (IOException e) {
            log.warn("resume verification I/O failure, starting fresh", e);
        }
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
            }
        }
    }

    private void announce(TrackerEvent event) {
        long left = Math.max(0, meta.length() - localCardinality() * meta.pieceLength());
        AnnounceRequest request = new AnnounceRequest(meta.infoHash(), peerId, config.listenPort(),
            uploaded.get(), downloaded.get(), left, event, 50);
        boolean anySuccess = false;
        for (List<String> tier : meta.trackerTiers()) {
            for (String url : tier) {
                try {
                    var response = trackerClient.announce(url, request);
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

    /** 出站/入站连接就绪（transport 在其连接线程上回调）。 */
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
        channel.setMessageListener(message -> handleMessage(session, message));
        channel.setCloseListener(cause -> peerClosed(session));
        // BEP 3：bitfield 是可选消息（无数据的客户端常直接省略，只用 have 逐片通告）。
        // 连接即注册空位图，否则 have-only 对端的分片永远不可见、调度器不会发出任何请求。
        scheduler.peerConnected(session.key, session.remote);
        if (localCardinality() > 0) {
            channel.write(new BitfieldMessage(localBytes()));
        }
        channel.write(Interested.INSTANCE);
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
            case UnsupportedMessage u -> {
            }
            case Cancel c -> {
            }
            case KeepAlive k -> {
            }
        }
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

    /** 齐件：哈希与落盘在监视器外（不阻塞该 Peer 后续块的事件循环处理），状态变更短暂持锁。 */
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
        double fraction = meta.pieceCount() == 0 ? 1.0 : (double) localCardinality() / meta.pieceCount();
        long remaining = Math.max(0, meta.length() - downloadedRemainingBasis());
        Long eta = downloadRate > 0 && remaining > 0 ? remaining * 1000 / downloadRate : null;
        return new ProgressSnapshot(fraction, downloaded.get(), uploaded.get(),
            downloadRate, uploadRate, peers.size(), availability(), eta);
    }

    /** 已完成字节数（ETA 基数）：已落件 + 在途组装块，末件按实际长度截断。 */
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

    /** 供 DefaultTorrentClient 出站连接与入站路由使用。 */
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

    /** 单个 Peer 的会话状态。pending/issued/currentPiece 由持有者线程在 session 监视器下访问。 */
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
        int currentPiece = -1;

        PeerSession(String key, PeerChannel channel, int pieceCount) {
            this.key = key;
            this.channel = channel;
            this.remote = new Bitfield(pieceCount);
        }
    }

    /** 在内存中按块槽位零拷贝组装：解码块直接挂引用，齐件后顺序喂摘要 + gather 落盘。 */
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

        /** 槽位校验：begin/length 必须与该槽的请求对齐（协议上对端只回我们请求过的块）。 */
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
