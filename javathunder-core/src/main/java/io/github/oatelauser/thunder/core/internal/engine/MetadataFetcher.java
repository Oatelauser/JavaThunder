package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.tracker.UdpTrackerClient;
import io.github.oatelauser.thunder.api.PeerDiscoverySource;
import org.jspecify.annotations.Nullable;
import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.tracker.AnnounceRequest;
import io.github.oatelauser.thunder.core.internal.tracker.PeerIds;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerClient;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerEvent;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerException;
import io.github.oatelauser.thunder.core.internal.wire.ExtendedMessage;
import io.github.oatelauser.thunder.core.internal.wire.Interested;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 磁力链接的元数据获取（B1：BEP 10 + BEP 9）。
 *
 * <p>流程：tracker（或后续 DHT）拿到 Peer 地址 → 连接并交换扩展握手（子 ID 0，
 * 声明 ut_metadata）→ 对端回自己的扩展握手（含 metadata_size 与它的 ut_metadata 子 ID）
 * → 按 16KiB 分块请求 {@code {msg_type:0, piece:n}} → 重组 info 字典 →
 * SHA-1 必须等于磁力的 info-hash（防错元数据）→ 交给正常下载会话。
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
    private final TrackerClient trackerClient;
    private final int listenPort;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final LinkedBlockingQueue<InetSocketAddress> candidates = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, MetadataSession> sessions = new ConcurrentHashMap<>();
    private final CountDownLatch finished = new CountDownLatch(1);

    public MetadataFetcher(byte[] infoHash, List<String> trackers, PeerTransport transport,
                           TrackerClient trackerClient, int listenPort) {
        this(infoHash, trackers, transport, trackerClient, listenPort, null);
    }

    @Nullable
    private final UdpTrackerClient udpTracker;

    public MetadataFetcher(byte[] infoHash, List<String> trackers, PeerTransport transport,
                           TrackerClient trackerClient, int listenPort,
                           @Nullable
                           PeerDiscoverySource discovery) {
        this(infoHash, trackers, transport, trackerClient, listenPort, discovery, null);
    }

    public MetadataFetcher(byte[] infoHash, List<String> trackers, PeerTransport transport,
                           TrackerClient trackerClient, int listenPort,
                           @Nullable PeerDiscoverySource discovery,
                           @Nullable UdpTrackerClient udpTracker) {
        this.infoHash = infoHash.clone();
        this.trackers = List.copyOf(trackers);
        this.transport = transport;
        this.trackerClient = trackerClient;
        this.listenPort = listenPort;
        this.discovery = discovery;
        this.udpTracker = udpTracker;
        this.peerId = PeerIds.generate();
    }

    @Nullable
    private final PeerDiscoverySource discovery;

    /** 异步拉取，完成后给出 info 字典的原始字节。 */
    public CompletableFuture<byte[]> fetch() {
        Thread.ofVirtual().name("javathunder-metadata").start(() -> {
            try {
                announceTrackers();
                discoverPeers(); // DHT 等去中心化来源（磁力无 tracker 时的主通道）
                connectLoop();
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.whenComplete((ignored, error) -> finished.countDown());
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
                candidates.offer(peer);
            }
        });
    }

    private void announceTrackers() {
        AnnounceRequest request = new AnnounceRequest(infoHash, peerId, listenPort,
            0, 0, 0, TrackerEvent.STARTED, 50);
        for (String url : trackers) {
            try {
                var response = UdpTrackerClient
                    .supports(url) && udpTracker != null
                    ? udpTracker.announce(url, request)
                    : trackerClient.announce(url, request);
                if (response.failureReason() == null) {
                    response.peers().forEach(candidates::offer);
                }
            } catch (TrackerException e) {
                log.debug("magnet tracker {} failed: {}", url, e.getMessage());
            }
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
            String key = address.getAddress().getHostAddress() + ":" + address.getPort();
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
        channel.setMessageListener((java.util.List<PeerWireMessage> messages) -> {
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
        BDict m = new BDict(java.util.Map.of(
            BString.of("ut_metadata"), new BInteger(OUR_UT_METADATA_ID)));
        java.util.Map<BString, BencodeValue> handshake = new java.util.TreeMap<>(BString.UNSIGNED_ORDER);
        handshake.put(BString.of("m"), m);
        handshake.put(BString.of("p"), new BInteger(listenPort));
        channel.write(new ExtendedMessage(0, Bencode.encode(new BDict(handshake))));
    }

    private boolean handle(MetadataSession session, PeerWireMessage message) {
        if (!(message instanceof ExtendedMessage extended)) {
            return true; // 非/扩展消息不影响元数据交换
        }
        if (extended.extendedId() == 0) {
            BDict handshake = decode(extended.payload());
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
        if (extended.extendedId() == OUR_UT_METADATA_ID && session.remoteUtMetadataId > 0) {
            // BEP 9 data 消息 = bencoded 头 + 原始 info 字节：必须用 decodeValue（容忍
            // 尾部数据）解析头——严格版 decode 会因 trailing data 抛错而丢弃所有分块。
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(extended.payload());
            BencodeValue headerValue;
            try {
                headerValue = Bencode.decodeValue(buf);
            } catch (RuntimeException e) {
                return true;
            }
            if (!(headerValue instanceof BDict response)) {
                return true;
            }
            int msgType = response.get("msg_type") instanceof BInteger t ? (int) t.value() : -1;
            int piece = response.get("piece") instanceof BInteger p ? (int) p.value() : -1;
            int totalPieces = (session.metadataSize + METADATA_BLOCK - 1) / METADATA_BLOCK;
            if (msgType != 1 || piece < 0 || piece >= totalPieces || session.metadataSize <= 0) {
                return true; // data 之外的响应（reject 等）忽略
            }
            // 头/data 分界 = 解码后的缓冲 position（decodeValue 停在值后，与 TorrentParser 同语义）
            int headerEnd = buf.position();
            int from = piece * METADATA_BLOCK;
            int to = (int) Math.min((long) from + METADATA_BLOCK, session.metadataSize);
            int dataLength = to - from;
            if (extended.payload().length - headerEnd < dataLength) {
                return true; // 截断
            }
            System.arraycopy(extended.payload(), headerEnd, session.metadata, from, dataLength);
            session.receivedPieces++;
            if (session.receivedPieces == totalPieces) {
                completeWith(session.metadata, session.metadataSize);
            }
        }
        return true;
    }

    private void requestBlocks(MetadataSession session) {
        session.metadata = new byte[session.metadataSize];
        session.receivedPieces = 0;
        int totalPieces = (session.metadataSize + METADATA_BLOCK - 1) / METADATA_BLOCK;
        for (int piece = 0; piece < totalPieces; piece++) {
            java.util.Map<BString, BencodeValue> request = new java.util.TreeMap<>(BString.UNSIGNED_ORDER);
            request.put(BString.of("msg_type"), new BInteger(0));
            request.put(BString.of("piece"), new BInteger(piece));
            session.channel.write(new ExtendedMessage(session.remoteUtMetadataId,
                Bencode.encode(new BDict(request))));
        }
    }

    private void completeWith(byte[] metadata, int size) {
        byte[] info = Arrays.copyOf(metadata, size);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(info);
            if (Arrays.equals(hash, infoHash)) {
                result.complete(info);
                closeAll();
            } else {
                log.debug("metadata hash mismatch from peer, discarding");
                // 换下一个 Peer 重来：关闭当前会话，连接循环仍在跑
            }
        } catch (NoSuchAlgorithmException e) {
            result.completeExceptionally(e);
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

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 单 Peer 的元数据交换状态。 */
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
