package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.PeerDiscoverySource;
import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import io.github.oatelauser.thunder.core.internal.peer.PeerIds;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerClient;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerEvent;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerGateway;
import io.github.oatelauser.thunder.core.internal.tracker.UdpTrackerClient;
import io.github.oatelauser.thunder.core.internal.wire.ExtendedMessage;
import io.github.oatelauser.thunder.core.internal.wire.Interested;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 磁力链接的元数据获取（B1：BEP 10 + BEP 9）。
 *
 * <p>流程：tracker（或后续 DHT）拿到 Peer 地址 → 连接并交换扩展握手（子 ID 0，
 * 声明 ut_metadata）→ 对端回自己的扩展握手（含 metadata_size 与它的 ut_metadata 子 ID）
 * → 按 16KiB 分块请求 {@code {msg_type:0, piece:n}} → 重组 info 字典 →
 * SHA-1 必须等于磁力的 info-hash（防错元数据）→ 交给正常下载会话。
 *
 * <p>announce 走 {@link TrackerAnnouncer}（与下载会话同源的 tier 失败转移 +
 * 全败指数退避；无监听面），候选入队过滤自连回声（{@link PeerAddresses}）。
 */
public final class MetadataFetcher {

    private static final Logger log = LoggerFactory.getLogger(MetadataFetcher.class);
    private static final int METADATA_BLOCK = 16 * 1024;
    private static final int OUR_UT_METADATA_ID = 1;
    private static final long TIMEOUT_MILLIS = 60_000;

    private final byte[] infoHash;
    private final byte[] peerId;
    private final List<String> trackers;
    private final PeerTransport transport;
    private final TrackerAnnouncer announcer;
    private final int listenPort;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final LinkedBlockingQueue<InetSocketAddress> candidates = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, MetadataSession> sessions = new ConcurrentHashMap<>();
    private final CountDownLatch finished = new CountDownLatch(1);

    public MetadataFetcher(byte[] infoHash, List<String> trackers, PeerTransport transport,
            TrackerClient trackerClient, int listenPort,
            @Nullable PeerDiscoverySource discovery,
            @Nullable UdpTrackerClient udpTracker) {
        this.infoHash = infoHash.clone();
        this.trackers = List.copyOf(trackers);
        this.transport = transport;
        this.peerId = PeerIds.generate();
        // 磁力阶段无已传输量/剩余量概念（元数据大小未知）：uploaded/downloaded/left 恒 0；
        // peer id 与线协议握手同一份（BEP 20 身份一致）
        this.announcer = new TrackerAnnouncer(infoHash, peerId, listenPort,
                List.of(List.copyOf(trackers)), new TrackerGateway(trackerClient, udpTracker),
                null, () -> 0L, this::offerCandidate, () -> 0L, () -> 0L);
        this.listenPort = listenPort;
        this.discovery = discovery;
    }

    @Nullable
    private final PeerDiscoverySource discovery;

    /**
     * 异步拉取，完成后给出 info 字典的原始字节。announce 周期循环与连接循环
     * 各占一条虚拟线程：announce 持续补给候选，连接循环消费直到成功或超时。
     */
    public CompletableFuture<byte[]> fetch() {
        Thread.ofVirtual().name("javathunder-metadata-announce").start(() -> {
            try {
                announceLoop();
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        Thread.ofVirtual().name("javathunder-metadata").start(() -> {
            try {
                connectLoop();
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.whenComplete((ignored, error) -> finished.countDown());
    }

    /**
     * announce 周期循环（与下载会话的 trackerLoop 同构）：首轮 STARTED、之后按
     * tracker 应答 interval 周期 NONE；tier 失败转移与全败指数退避由
     * {@link TrackerAnnouncer} 承担。每拍顺带补充 DHT 等去中心化候选
     * （磁力无 tracker 时的主通道）。
     */
    private void announceLoop() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        boolean first = true;
        while (!result.isDone() && System.currentTimeMillis() < deadline) {
            if (!trackers.isEmpty()) {
                announcer.announce(first ? TrackerEvent.STARTED : TrackerEvent.NONE);
            }
            first = false;
            discoverPeers();
            if (sleepMillis(Math.max(2, announcer.intervalSeconds()) * 1000L)) {
                return;
            }
        }
    }

    private void discoverPeers() {
        if (discovery == null) {
            return;
        }
        discovery.getPeers(infoHash).whenComplete((peers, error) -> {
            if (error != null) {
                log.debug("peer discovery failed: {}", error.toString());
                return;
            }
            for (InetSocketAddress peer : peers) {
                offerCandidate(peer);
            }
        });
    }

    /**
     * 候选入队：过滤自连回声与已连接会话——与下载会话同一判定（{@link PeerAddresses}）。
     */
    private void offerCandidate(InetSocketAddress address) {
        if (PeerAddresses.isSelfConnection(address, listenPort)) {
            return; // 我们自己
        }
        if (!sessions.containsKey(PeerAddresses.key(address))) {
            candidates.offer(address);
        }
    }

    private void connectLoop() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!result.isDone() && System.currentTimeMillis() < deadline) {
            InetSocketAddress address = candidates.poll();
            if (address == null) {
                sleepMillis(200);
                continue;
            }
            if (sessions.size() >= 8) {
                continue;
            }
            String key = PeerAddresses.key(address);
            if (sessions.containsKey(key)) {
                continue;
            }
            transport.connect(address, infoHash, new TransportHandler() {
                @Override
                public void onConnected(PeerChannel channel) {
                    onPeerConnected(key, channel);
                }

                @Override
                public void onConnectFailed(InetSocketAddress failed, Throwable cause) {
                    log.debug("magnet connect {} failed: {}", failed, cause.toString());
                }
            });
        }
        if (!result.isDone()) {
            result.completeExceptionally(new IllegalStateException(
                    "metadata fetch timed out after " + TIMEOUT_MILLIS + "ms (peers=" + sessions.size()
                            + ", pending=" + candidates.size() + ")"));
        }
    }

    private void onPeerConnected(String key, PeerChannel channel) {
        if (result.isDone()) {
            channel.close();
            return;
        }
        MetadataSession session = new MetadataSession(key, channel);
        sessions.put(key, session);
        channel.setMessageListener((List<PeerWireMessage> messages) -> {
            for (PeerWireMessage message : messages) {
                handle(session, message);
            }
        });
        channel.setCloseListener(cause -> sessions.remove(key));
        channel.write(Interested.INSTANCE);
        if (!channel.remoteSupportsExtensions()) {
            return; // 对端握手未声明 BEP 10：ut_metadata 无从协商，等下一个 Peer
        }
        // BEP 10 扩展握手：声明我们支持 ut_metadata（子 ID 1）
        BDict m = new BDict(Map.of(
                BString.of("ut_metadata"), new BInteger(OUR_UT_METADATA_ID)));
        Map<BString, BencodeValue> handshake = new TreeMap<>(BString.UNSIGNED_ORDER);
        handshake.put(BString.of("m"), m);
        handshake.put(BString.of("p"), new BInteger(listenPort));
        channel.write(new ExtendedMessage(0, Bencode.encode(new BDict(handshake))));
    }

    /**
     * 消息分发：只处理 BEP 10 扩展消息（握手与 ut_metadata data），其余不影响元数据交换。
     */
    private boolean handle(MetadataSession session, PeerWireMessage message) {
        if (!(message instanceof ExtendedMessage extended)) {
            return true; // 非扩展消息不影响元数据交换
        }
        if (extended.extendedId() == 0) {
            return handleHandshake(session, extended.payload());
        }
        if (extended.extendedId() == OUR_UT_METADATA_ID && session.remoteUtMetadataId > 0) {
            handleData(session, extended.payload());
        }
        return true;
    }

    /**
     * BEP 10 扩展握手：协商对端的 ut_metadata 子 ID 与 metadata_size，两者齐备即开始请求分块。
     */
    private boolean handleHandshake(MetadataSession session, byte[] payload) {
        BDict handshake = decode(payload);
        if (handshake == null) {
            return true;
        }
        BencodeValue utMetadata = handshake.get("m") instanceof BDict m
                ? m.get("ut_metadata") : null;
        if (utMetadata instanceof BInteger id && id.value() > 0) {
            session.remoteUtMetadataId = (int) id.value();
            BencodeValue size = handshake.get("metadata_size");
            if (size instanceof BInteger metadataSize
                    && metadataSize.value() > 0 && metadataSize.value() <= 8 * 1024 * 1024) {
                session.metadataSize = (int) metadataSize.value();
                requestBlocks(session);
            }
        }
        return true;
    }

    /**
     * BEP 9 data 分块：载荷 = bencoded 头 + 原始 info 字节。头必须用 decodeValue
     * （容忍尾部数据）解析——严格版 decode 会因 trailing data 抛错而丢弃所有分块；
     * 头/数据分界取解码后的缓冲 position（与 TorrentParser 同语义）。
     */
    private void handleData(MetadataSession session, byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        BencodeValue headerValue;
        try {
            headerValue = Bencode.decodeValue(buf);
        } catch (RuntimeException e) {
            return;
        }
        if (!(headerValue instanceof BDict response)) {
            return;
        }
        int msgType = response.get("msg_type") instanceof BInteger t ? (int) t.value() : -1;
        int piece = response.get("piece") instanceof BInteger p ? (int) p.value() : -1;
        int totalPieces = (session.metadataSize + METADATA_BLOCK - 1) / METADATA_BLOCK;
        if (msgType != 1 || piece < 0 || piece >= totalPieces || session.metadataSize <= 0) {
            return; // data 之外的响应（reject 等）忽略
        }
        copyBlock(session, payload, buf.position(), piece, totalPieces);
    }

    /**
     * 把 data 分块的原始字节拷入重组缓冲对应区间；收齐全部分块即整体交付 SHA-1 校验。
     */
    private void copyBlock(MetadataSession session, byte[] payload, int headerEnd,
            int piece, int totalPieces) {
        int from = piece * METADATA_BLOCK;
        int to = (int) Math.min((long) from + METADATA_BLOCK, session.metadataSize);
        int dataLength = to - from;
        if (payload.length - headerEnd < dataLength) {
            return; // 截断
        }
        System.arraycopy(payload, headerEnd, session.metadata, from, dataLength);
        session.receivedPieces++;
        if (session.receivedPieces == totalPieces) {
            completeWith(session.metadata, session.metadataSize);
        }
    }

    private void requestBlocks(MetadataSession session) {
        session.metadata = new byte[session.metadataSize];
        session.receivedPieces = 0;
        int totalPieces = (session.metadataSize + METADATA_BLOCK - 1) / METADATA_BLOCK;
        for (int piece = 0; piece < totalPieces; piece++) {
            Map<BString, BencodeValue> request = new TreeMap<>(BString.UNSIGNED_ORDER);
            request.put(BString.of("msg_type"), new BInteger(0));
            request.put(BString.of("piece"), new BInteger(piece));
            session.channel.write(new ExtendedMessage(session.remoteUtMetadataId,
                    Bencode.encode(new BDict(request))));
        }
    }

    /**
     * 整体交付前校验 info 字典哈希：v1/hybrid 磁力对 SHA-1；v2-only 磁力（btmh）的
     * 20 字节身份是截断 SHA-256（BEP 52，与线协议握手一致）——任一匹配即通过，
     * 由 info 字典实际形态决定哪一个成立。
     */
    private void completeWith(byte[] metadata, int size) {
        byte[] info = Arrays.copyOf(metadata, size);
        if (matchesV1(info) || matchesV2(info)) {
            result.complete(info);
            closeAll();
        } else {
            log.debug("metadata hash mismatch from peer, discarding");
            // 换下一个 Peer 重来：关闭当前会话，连接循环仍在跑
        }
    }

    private boolean matchesV1(byte[] info) {
        try {
            return Arrays.equals(MessageDigest.getInstance("SHA-1").digest(info), infoHash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean matchesV2(byte[] info) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(info);
            return Arrays.equals(Arrays.copyOf(digest, 20), infoHash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void closeAll() {
        for (MetadataSession session : sessions.values()) {
            session.channel.close();
        }
        sessions.clear();
    }

    private static BDict decode(byte[] payload) {
        try {
            return Bencode.decode(payload) instanceof BDict dict ? dict : null;
        } catch (RuntimeException e) {
            return null;
        }
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
     * 单 Peer 的元数据交换状态。
     */
    private static final class MetadataSession {
        final String key;
        final PeerChannel channel;
        volatile int remoteUtMetadataId = -1;
        volatile int metadataSize = -1;
        volatile byte[] metadata;
        volatile int receivedPieces;

        MetadataSession(String key, PeerChannel channel) {
            this.key = key;
            this.channel = channel;
        }
    }
}
