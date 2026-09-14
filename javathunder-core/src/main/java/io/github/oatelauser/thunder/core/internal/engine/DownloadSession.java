package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.peer.PeerConnection;
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
import io.github.oatelauser.thunder.core.internal.wire.Interested;
import io.github.oatelauser.thunder.core.internal.wire.KeepAlive;
import io.github.oatelauser.thunder.core.internal.wire.NotInterested;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import io.github.oatelauser.thunder.core.internal.wire.PieceMessage;
import io.github.oatelauser.thunder.core.internal.wire.Request;
import io.github.oatelauser.thunder.core.internal.wire.Unchoke;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个种子的下载会话：tracker 轮询、Peer 连接池、请求管线、校验落盘、断点续存、
 * choking 与事件分发。全部后台线程为虚拟线程（ADR-0001）。
 */
public final class DownloadSession {

    private static final Logger log = LoggerFactory.getLogger(DownloadSession.class);
    private static final int PIPELINE_DEPTH = 8;
    private static final int MAX_BAD_PIECES_PER_PEER = 2;
    private static final int CONNECT_TIMEOUT_MILLIS = 8000;

    public record SessionConfig(int maxPeers, int listenPort,
                                RateLimiter globalDownload, RateLimiter globalUpload) {
    }

    private final TorrentMetadata meta;
    private final DownloadOptions options;
    private final SessionConfig config;
    private final TrackerClient trackerClient;
    private final byte[] peerId;
    private final Executor eventExecutor;
    private final StorageManager storage;
    private final Path resumeFile;
    private final PieceScheduler scheduler;
    private final ChokingManager choking;
    private final Random random = new Random();
    private final long startedAtMillis = System.currentTimeMillis();

    private final Bitfield local;
    private final CompletableFuture<DownloadResult> future = new CompletableFuture<>();
    private final CopyOnWriteArrayList<TaskListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, PeerSession> peers = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<InetSocketAddress> candidates = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Integer> badPiecesByPeer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<BlockRequest>> receivedBlocks = new ConcurrentHashMap<>();
    private final Set<Integer> activePieces = ConcurrentHashMap.newKeySet();
    private final AtomicLong downloaded = new AtomicLong();
    private final AtomicLong uploaded = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile TaskState state = TaskState.QUEUED;
    private volatile int announceIntervalSeconds = 5;
    private volatile long downloadRate;
    private volatile long uploadRate;

    public DownloadSession(TorrentMetadata meta, DownloadOptions options, SessionConfig config,
                           TrackerClient trackerClient, Executor eventExecutor, byte[] peerId)
        throws IOException {
        this.meta = meta;
        this.options = options;
        this.config = config;
        this.trackerClient = trackerClient;
        this.eventExecutor = eventExecutor;
        this.peerId = peerId.clone();
        this.storage = new StorageManager(meta, options.targetDir());
        this.resumeFile = storage.partFile().resolveSibling(meta.name() + ".jt-resume");
        this.local = new Bitfield(meta.pieceCount());
        this.scheduler = new PieceScheduler(meta.pieceCount(), meta.pieceLength(), meta.length(), random);
        this.choking = new ChokingManager(random);
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
        for (List<String> tier : meta.trackerTiers()) {
            for (String url : tier) {
                try {
                    var response = trackerClient.announce(url, request);
                    if (response.failureReason() != null) {
                        log.warn("tracker {} rejected announce: {}", url, response.failureReason());
                        continue;
                    }
                    announceIntervalSeconds = response.interval();
                    for (InetSocketAddress peer : response.peers()) {
                        offerCandidate(peer);
                    }
                    return;
                } catch (TrackerException e) {
                    log.debug("tracker {} failed: {}", url, e.getMessage());
                }
            }
        }
        log.warn("announce {} failed on all trackers", event);
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
            String key = key(address);
            if (peers.size() >= config.maxPeers() || peers.containsKey(key)) {
                continue;
            }
            try {
                PeerConnection connection =
                    PeerConnection.connect(address, meta.infoHash(), peerId, CONNECT_TIMEOUT_MILLIS);
                PeerSession session = new PeerSession(key, connection, meta.pieceCount());
                peers.put(key, session);
                Thread.ofVirtual().name("javathunder-peer-" + key).start(() -> runPeer(session));
            } catch (IOException e) {
                log.debug("connect to {} failed: {}", key, e.toString());
            }
        }
    }

    private void runPeer(PeerSession session) {
        try (PeerConnection ignored = session.connection) {
            if (localCardinality() > 0) {
                session.connection.write(new BitfieldMessage(localBytes()));
            }
            session.connection.write(Interested.INSTANCE);
            while (running.get() && (state == TaskState.DOWNLOADING || state == TaskState.SEEDING)) {
                if (state == TaskState.DOWNLOADING && !session.peerChokingUs) {
                    refillRequests(session);
                }
                PeerWireMessage message = session.connection.read();
                handleMessage(session, message);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("peer {} disconnected: {}", session.key, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 关闭中（限速等待被中断）
        } finally {
            peers.remove(session.key);
            scheduler.peerDisconnected(session.key);
            releaseAssignment(session);
        }
    }

    /** 入站连接（路由方已预读握手）。 */
    public void handleInbound(Socket socket, Handshake remoteHandshake) {
        if (!running.get() || !Arrays.equals(remoteHandshake.infoHash(), meta.infoHash())) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }
        try {
            PeerConnection connection =
                PeerConnection.acceptWithHandshake(socket, remoteHandshake, meta.infoHash(), peerId);
            InetSocketAddress address = connection.remoteAddress();
            String key = address.getAddress() == null
                ? address.getHostString() + ":" + address.getPort() : key(address);
            if (peers.containsKey(key)) {
                connection.close();
                return;
            }
            PeerSession session = new PeerSession(key, connection, meta.pieceCount());
            peers.put(key, session);
            Thread.ofVirtual().name("javathunder-peer-" + key).start(() -> runPeer(session));
        } catch (IOException e) {
            log.debug("inbound peer rejected: {}", e.toString());
        }
    }

    private void handleMessage(PeerSession session, PeerWireMessage message)
        throws IOException, InterruptedException {
        switch (message) {
            case Choke c -> {
                session.peerChokingUs = true;
                releaseAssignment(session);
            }
            case Unchoke u -> session.peerChokingUs = false;
            case Interested i -> session.remoteInterested = true;
            case NotInterested n -> session.remoteInterested = false;
            case Have h -> scheduler.peerHave(session.key, h.pieceIndex());
            case BitfieldMessage b -> {
                session.remote = Bitfield.fromBytes(b.bits(), meta.pieceCount());
                scheduler.peerConnected(session.key, session.remote);
            }
            case Request r -> serveUpload(session, r);
            case PieceMessage p -> onPieceData(session, p);
            case Cancel c -> {
            }
            case KeepAlive k -> {
            }
        }
    }

    private void refillRequests(PeerSession session) throws IOException {
        while (session.issued.size() < PIPELINE_DEPTH) {
            if (session.pending.isEmpty()) {
                int piece = pickPieceFor(session);
                if (piece < 0) {
                    return;
                }
                session.currentPiece = piece;
                activePieces.add(piece);
                Set<BlockRequest> received = receivedBlocks.get(piece);
                for (BlockRequest block : scheduler.blocksOf(piece)) {
                    if (received == null || !received.contains(block)) {
                        session.pending.addLast(block);
                    }
                }
                if (session.pending.isEmpty()) {
                    activePieces.remove(piece);
                    session.currentPiece = -1;
                    continue; // 该 piece 全部在途（endgame 由其他 peer 重复请求）
                }
            }
            BlockRequest block = session.pending.pollFirst();
            if (block == null) {
                return;
            }
            session.connection.write(new Request(block.pieceIndex(), block.begin(), block.length()));
            scheduler.markInFlight(block);
            session.issued.add(block);
        }
    }

    private int pickPieceFor(PeerSession session) {
        int bestFree = -1;
        int bestFreeAvailability = Integer.MAX_VALUE;
        int bestBusy = -1;
        int bestBusyAvailability = Integer.MAX_VALUE;
        for (int i = 0; i < meta.pieceCount(); i++) {
            if (localHas(i) || !session.remote.has(i) || !hasMissingBlock(i)) {
                continue;
            }
            int availability = scheduler.availability(i);
            if (!activePieces.contains(i)) {
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
        Set<BlockRequest> received = receivedBlocks.get(piece);
        if (received == null) {
            return true;
        }
        for (BlockRequest block : scheduler.blocksOf(piece)) {
            if (!received.contains(block)) {
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

    private void onPieceData(PeerSession session, PieceMessage message)
        throws IOException, InterruptedException {
        int piece = message.pieceIndex();
        if (piece < 0 || piece >= meta.pieceCount()) {
            return;
        }
        BlockRequest block = new BlockRequest(piece, message.begin(), message.block().length);
        if (localHas(piece) || receivedBlocks.get(piece) != null
            && receivedBlocks.get(piece).contains(block)) {
            session.issued.remove(block);
            scheduler.clearInFlight(block);
            return; // 重复投递（endgame），忽略
        }
        config.globalDownload().acquire(message.block().length);
        storage.writeBlock(piece, message.begin(), message.block());
        session.issued.remove(block);
        scheduler.clearInFlight(block);
        downloaded.addAndGet(message.block().length);
        choking.recordReceived(session.key, message.block().length);
        receivedBlocks.computeIfAbsent(piece, k -> ConcurrentHashMap.newKeySet()).add(block);
        if (receivedBlocks.get(piece).size() == scheduler.blocksOf(piece).size()) {
            receivedBlocks.remove(piece);
            activePieces.remove(piece);
            releaseAssignment(session);
            onPieceComplete(piece, session);
        }
    }

    private void onPieceComplete(int piece, PeerSession source) throws IOException {
        if (storage.verifyPiece(piece)) {
            localSet(piece);
            saveResumeQuietly();
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
            }
        } else {
            storage.clearPiece(piece);
            int bad = badPiecesByPeer.merge(source.key, 1, Integer::sum);
            log.warn("piece {} failed verification from {} (bad #{})", piece, source.key, bad);
            if (bad >= MAX_BAD_PIECES_PER_PEER) {
                try {
                    source.connection.close(); // 恶意/损坏数据源，断开
                } catch (IOException ignored) {
                }
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
        try {
            byte[] block = storage.readBlock(request.pieceIndex(), request.begin(), request.length());
            config.globalUpload().acquire(block.length);
            session.connection.write(
                new PieceMessage(request.pieceIndex(), request.begin(), block));
            uploaded.addAndGet(block.length);
            choking.recordSent(session.key, block.length);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalArgumentException e) {
            log.debug("cannot serve block to {}: {}", session.key, e.toString());
        }
    }

    private void broadcast(PeerWireMessage message) {
        for (PeerSession session : peers.values()) {
            try {
                session.connection.write(message);
            } catch (IOException e) {
                log.debug("broadcast to {} failed: {}", session.key, e.toString());
            }
        }
    }

    private void closeAllPeers() {
        for (PeerSession session : peers.values()) {
            try {
                session.connection.close();
            } catch (IOException ignored) {
            }
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
                    try {
                        session.connection.write(shouldUnchoke ? Unchoke.INSTANCE : Choke.INSTANCE);
                    } catch (IOException e) {
                        log.debug("choke write to {} failed: {}", session.key, e.toString());
                    }
                }
            }
            tick++;
        }
    }

    private void progressLoop() {
        long lastDownloaded = downloaded.get();
        long lastUploaded = uploaded.get();
        long lastMillis = System.currentTimeMillis();
        while (running.get()) {
            if (sleepMillis(500)) {
                return;
            }
            long now = System.currentTimeMillis();
            long dt = Math.max(1, now - lastMillis);
            downloadRate = (downloaded.get() - lastDownloaded) * 1000 / dt;
            uploadRate = (uploaded.get() - lastUploaded) * 1000 / dt;
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

    // ---------------------------------------------------------------- accessors

    public CompletableFuture<DownloadResult> future() {
        return future;
    }

    public TaskState state() {
        return state;
    }

    public ProgressSnapshot snapshot() {
        double fraction = meta.pieceCount() == 0 ? 1.0 : (double) localCardinality() / meta.pieceCount();
        return new ProgressSnapshot(fraction, downloaded.get(), uploaded.get(),
            downloadRate, uploadRate, peers.size(), availability());
    }

    public void addListener(TaskListener listener) {
        listeners.add(listener);
    }

    public Path partFile() {
        return storage.partFile();
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

    /** 单个 Peer 的会话状态，仅被该 Peer 的虚拟线程读写（remote 除外）。 */
    private static final class PeerSession {
        final String key;
        final PeerConnection connection;
        final ArrayDeque<BlockRequest> pending = new ArrayDeque<>();
        final Set<BlockRequest> issued = ConcurrentHashMap.newKeySet();
        volatile Bitfield remote;
        volatile boolean peerChokingUs = true;
        volatile boolean weChokingThem = true;
        volatile boolean remoteInterested;
        int currentPiece = -1;

        PeerSession(String key, PeerConnection connection, int pieceCount) {
            this.key = key;
            this.connection = connection;
            this.remote = new Bitfield(pieceCount);
        }
    }
}
