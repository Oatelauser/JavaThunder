package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.PeerDiscoverySource;
import io.github.oatelauser.thunder.core.internal.metainfo.MerkleHashes;
import io.github.oatelauser.thunder.core.internal.metainfo.MerkleProofs;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.peer.PeerIds;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerClient;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerEvent;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerGateway;
import io.github.oatelauser.thunder.core.internal.tracker.UdpTrackerClient;
import io.github.oatelauser.thunder.core.internal.wire.HashReject;
import io.github.oatelauser.thunder.core.internal.wire.HashRequest;
import io.github.oatelauser.thunder.core.internal.wire.Hashes;
import io.github.oatelauser.thunder.core.internal.wire.Interested;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v2-only 磁力的第二段：piece-layer 哈希带获取（BEP 52 hash request/hashes）。
 *
 * <p>为什么需要：ut_metadata（BEP 9）只能带回 info 字典——含 file tree 与每文件
 * pieces root，而 piece layers 是 .torrent 顶层字段、不在 info 内。多 piece 文件的
 * v2 校验唯一来源是层带，本组件通过与对端哈希交换补齐：按 512 对齐块请求（libtorrent
 * 同形），每块携带到 pieces root 的 Merkle 证明，验证通过才装配；整带收齐后再做
 * 折叠到 root 的终检（与 .torrent 加载校验同源）。
 *
 * <p>骨架与 {@link MetadataFetcher} 同源：announce 循环补给候选 + 连接循环消费；
 * 会话建立后批量发出全部待取块，验证失败保留重试，hash reject 则关闭该 Peer 换源。
 */
public final class PieceLayerFetcher {

    private static final Logger log = LoggerFactory.getLogger(PieceLayerFetcher.class);
    private static final long TIMEOUT_MILLIS = 60_000;
    private static final int MAX_CHUNK = 512;
    /** 并发哈希交换会话上限：候选多时丢弃而非排队（announce 循环会周期性重新补给）。 */
    private static final int MAX_SESSIONS = 8;

    private final TorrentMetadata meta;
    private final byte[] infoHash;
    private final List<String> trackers;
    private final PeerTransport transport;
    private final int listenPort;
    @Nullable
    private final PeerDiscoverySource discovery;
    private final TrackerAnnouncer announcer;
    private final CompletableFuture<TorrentMetadata> result = new CompletableFuture<>();

    /** 待取块：key = {@code rootHex@index}。 */
    private final Map<String, Chunk> pending = new ConcurrentHashMap<>();
    /** rootHex → 层带装配缓冲 + 剩余块计数。 */
    private final Map<String, Strip> strips = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<InetSocketAddress> candidates = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, PeerChannel> sessions = new ConcurrentHashMap<>();

    /**
     * @param lazy 磁力第一段产物：v2 元数据，多 piece 实文件的 pieceLayer 为 null。
     */
    public PieceLayerFetcher(TorrentMetadata lazy, List<String> trackers,
            PeerTransport transport, TrackerClient trackerClient, int listenPort,
            @Nullable PeerDiscoverySource discovery, @Nullable UdpTrackerClient udpTracker) {
        this.meta = lazy;
        this.infoHash = lazy.infoHash().clone();
        this.trackers = List.copyOf(trackers);
        this.transport = transport;
        this.listenPort = listenPort;
        this.discovery = discovery;
        this.announcer = new TrackerAnnouncer(infoHash, PeerIds.generate(), listenPort,
                List.of(List.copyOf(trackers)), new TrackerGateway(trackerClient, udpTracker),
                null, () -> 0L, this::offerCandidate, () -> 0L, () -> 0L);
        planChunks();
    }

    /**
     * 按文件规划待取块（多 piece 实文件）：512 对齐、尾块向上取 2 的幂（含填充段，
     * 与 libtorrent 请求形状一致）；proofLayers = log2(填充宽度) − 1（其上全部
     * uncle 层），证明个数收发两侧按同一公式推导。
     */
    private void planChunks() {
        int pieceLayer = MerkleProofs.log2((int) (meta.pieceLength() / (16 * 1024)));
        for (TorrentMetadata.TorrentFile file : meta.files()) {
            if (file.padding() || file.piecesRoot() == null
                    || file.length() <= meta.pieceLength()) {
                continue; // 单 piece 文件：pieces root 即哈希，无需层带
            }
            int filePieces = (int) ((file.length() + meta.pieceLength() - 1) / meta.pieceLength());
            int padded = MerkleProofs.nextPow2(filePieces);
            int height = MerkleProofs.log2(padded) + pieceLayer;
            Strip strip = new Strip(file.piecesRoot(), filePieces);
            for (int i = 0; i < filePieces; i += MAX_CHUNK) {
                int count = Math.min(MAX_CHUNK, MerkleProofs.nextPow2(filePieces - i));
                strip.remaining.incrementAndGet();
                pending.put(chunkKey(file.piecesRoot(), i), new Chunk(file.piecesRoot(), i,
                        count, pieceLayer, MerkleProofs.log2(padded) - 1, height, strip));
            }
            strips.put(HexFormat.of().formatHex(file.piecesRoot()), strip);
        }
    }

    /**
     * 异步补齐层带；无需补齐（无多 piece 文件）时立即以原元数据完成。
     */
    public CompletableFuture<TorrentMetadata> fetch() {
        if (pending.isEmpty()) {
            result.complete(meta);
            return result;
        }
        Thread.ofVirtual().name("javathunder-layers-announce").start(this::announceLoop);
        Thread.ofVirtual().name("javathunder-layers").start(this::connectLoop);
        return result;
    }

    private void announceLoop() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        boolean first = true;
        while (!result.isDone() && System.currentTimeMillis() < deadline) {
            if (!trackers.isEmpty()) {
                announcer.announce(first ? TrackerEvent.STARTED : TrackerEvent.NONE);
            }
            first = false;
            if (discovery != null) {
                discovery.getPeers(infoHash).whenComplete((peers, error) -> {
                    if (error == null) {
                        peers.forEach(this::offerCandidate);
                    }
                });
            }
            if (sleepMillis(Math.max(2, announcer.intervalSeconds()) * 1000L)) {
                return;
            }
        }
    }

    private void offerCandidate(InetSocketAddress address) {
        if (!PeerAddresses.isSelfConnection(address, listenPort)
                && !sessions.containsKey(PeerAddresses.key(address))) {
            candidates.offer(address);
        }
    }

    private void connectLoop() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!result.isDone() && System.currentTimeMillis() < deadline) {
            InetSocketAddress address = candidates.poll();
            if (address == null) {
                if (sleepMillis(200)) {
                    return;
                }
                continue;
            }
            if (sessions.size() >= MAX_SESSIONS) {
                continue;
            }
            connectOne(address);
        }
        if (!result.isDone()) {
            result.completeExceptionally(new IllegalStateException("piece layer fetch timed out"
                    + " after " + TIMEOUT_MILLIS + "ms (" + pending.size() + " chunks pending)"));
            closeAll();
        }
    }

    private void connectOne(InetSocketAddress address) {
        String key = PeerAddresses.key(address);
        if (sessions.containsKey(key)) {
            return;
        }
        transport.connect(address, infoHash, new TransportHandler() {
            @Override
            public void onConnected(PeerChannel channel) {
                onPeerConnected(key, channel);
            }

            @Override
            public void onConnectFailed(InetSocketAddress failed, Throwable cause) {
                log.debug("layer fetch connect {} failed: {}", failed, cause.toString());
            }
        });
    }

    private void onPeerConnected(String key, PeerChannel channel) {
        if (result.isDone() || !channel.remoteSupportsV2()) {
            channel.close(); // 对端未声明 BEP 52：无从交换哈希
            return;
        }
        sessions.put(key, channel);
        channel.setCloseListener(cause -> sessions.remove(key));
        channel.setMessageListener(this::handleMessages);
        channel.write(Interested.INSTANCE);
        List<PeerWireMessage> batch = new ArrayList<>(pending.size());
        for (Chunk chunk : pending.values()) {
            batch.add(new HashRequest(chunk.piecesRoot(), chunk.baseLayer(), chunk.index(),
                    chunk.count(), chunk.proofLayers()));
        }
        channel.write(batch); // 批量一次刷出（多块合一唤醒）
    }

    private void handleMessages(List<PeerWireMessage> messages) {
        for (PeerWireMessage message : messages) {
            if (result.isDone()) {
                return;
            }
            if (message instanceof Hashes hashes) {
                onHashes(hashes);
            } else if (message instanceof HashReject reject) {
                onReject(reject);
            }
        }
    }

    private void onHashes(Hashes message) {
        Chunk chunk = pending.get(chunkKey(message.piecesRoot(), message.index()));
        if (chunk == null) {
            return; // 已被其他会话填充（先到先得），丢弃重复应答
        }
        boolean shapeOk = message.baseLayer() == chunk.baseLayer()
                && message.length() == chunk.count()
                && message.proofLayers() == chunk.proofLayers()
                && message.hashes().size() == chunk.count();
        if (!shapeOk || !MerkleProofs.verifyChunk(chunk.piecesRoot(), chunk.baseLayer(),
                chunk.index(), message.hashes(), message.proof(), chunk.treeHeight())) {
            log.debug("layer chunk proof failed at index {}; keeping for retry", chunk.index());
            return; // 验证失败：保留 pending，其他会话/Peer 重试
        }
        fillChunk(chunk, message.hashes());
    }

    /**
     * 验证通过后的装配：先原子占位（{@code remove(key, value)} 只允许一个会话胜出——
     * 请求批量发给全部对端，两会话并发交付同一 chunk 时若用 get-then-remove 的
     * check-then-act，双方都会 decrement，计数越过 0 永不归零、整带终检不触发），
     * 胜者拷入实段（越尾填充段丢弃）→ 剩余清零触发终检。
     */
    private void fillChunk(Chunk chunk, List<byte[]> hashes) {
        if (!pending.remove(chunkKey(chunk.piecesRoot(), chunk.index()), chunk)) {
            return; // 输给了并发交付同一 chunk 的会话：本份丢弃
        }
        int real = Math.min(chunk.count(), chunk.strip().filePieces - chunk.index());
        for (int i = 0; i < real; i++) {
            System.arraycopy(hashes.get(i), 0, chunk.strip().buffer,
                    (chunk.index() + i) * MerkleHashes.HASH_WIDTH, MerkleHashes.HASH_WIDTH);
        }
        if (chunk.strip().remaining.decrementAndGet() == 0) {
            verifyStrip(chunk.strip());
        }
    }

    /** 整带终检：折叠必须等于 pieces root（与 .torrent 加载的 validateLayers 同源）。 */
    private void verifyStrip(Strip strip) {
        List<byte[]> layer = new ArrayList<>(strip.filePieces);
        for (int i = 0; i < strip.filePieces; i++) {
            layer.add(Arrays.copyOfRange(strip.buffer,
                    i * MerkleHashes.HASH_WIDTH, (i + 1) * MerkleHashes.HASH_WIDTH));
        }
        int pieceLayer = MerkleProofs.log2((int) (meta.pieceLength() / (16 * 1024)));
        if (!MessageDigest.isEqual(
                MerkleHashes.rootOfLayer(layer, pieceLayer), strip.root)) {
            result.completeExceptionally(new IllegalStateException(
                    "assembled piece layer does not fold to pieces root"));
            closeAll();
            return;
        }
        if (pending.isEmpty()) {
            result.complete(assemble());
            closeAll();
        }
    }

    private TorrentMetadata assemble() {
        List<TorrentMetadata.TorrentFile> filled = new ArrayList<>(meta.files().size());
        for (TorrentMetadata.TorrentFile file : meta.files()) {
            Strip strip = file.piecesRoot() == null ? null
                    : strips.get(HexFormat.of().formatHex(file.piecesRoot()));
            filled.add(strip == null ? file
                    : new TorrentMetadata.TorrentFile(file.path(), file.offset(), file.length(),
                            file.piecesRoot(), file.padding(), strip.buffer.clone()));
        }
        return new TorrentMetadata(meta.infoHash(), meta.announce(), meta.announceList(),
                meta.comment(), meta.createdBy(), meta.creationDateSec(), meta.name(),
                meta.length(), meta.pieceLength(), meta.pieces(), meta.privateFlag(), filled,
                meta.webSeeds(), meta.version(), meta.infoHashV2());
    }

    private void onReject(HashReject reject) {
        // 拒答不消除 pending（其他会话可能可服务）；本会话保留——同一对端仍可能
        // 应答其他区间，重试由多会话并发发出的同一批量请求自然覆盖
        log.debug("peer rejected hash request at index {}", reject.index());
    }

    private void closeAll() {
        for (PeerChannel channel : sessions.values()) {
            channel.close();
        }
        sessions.clear();
    }

    private static String chunkKey(byte[] piecesRoot, int index) {
        return HexFormat.of().formatHex(piecesRoot) + "@" + index;
    }

    /**
     * @return true = 被中断（调用方据此退出循环）。
     */
    private static boolean sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /**
     * 单个待取块：pieces root + 区间参数 + 验证/装配上下文。
     */
    private record Chunk(byte[] piecesRoot, int index, int count, int baseLayer,
            int proofLayers, int treeHeight, Strip strip) {
    }

    /**
     * 一个文件的层带装配状态：root、真实 piece 数、装配缓冲与剩余块计数。
     */
    private static final class Strip {
        final byte[] root;
        final int filePieces;
        final byte[] buffer;
        final AtomicInteger remaining = new AtomicInteger();

        Strip(byte[] root, int filePieces) {
            this.root = root.clone();
            this.filePieces = filePieces;
            this.buffer = new byte[filePieces * MerkleHashes.HASH_WIDTH];
        }
    }
}
