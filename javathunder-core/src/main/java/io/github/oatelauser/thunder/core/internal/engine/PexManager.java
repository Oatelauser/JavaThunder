package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.wire.CompactPeer;
import io.github.oatelauser.thunder.core.internal.wire.ExtendedMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * BEP 10/11 ut_pex 对等交换：构造我们的扩展握手（声明 ut_pex 子 ID）、解析对端
 * 握手与 added 紧凑表、周期向已协商对端广播当前连接表。BEP 27：private 种子
 * 全程禁用——不协商、不接收、不广播（仅 tracker 通道发现 peer）。
 */
final class PexManager {

    /**
     * BEP 11：我们侧 ut_pex 子 ID（对端用协商值发给我们，我们统一用 2）。
     */
    static final int UT_PEX_ID = 2;

    private static final Logger log = LoggerFactory.getLogger(PexManager.class);

    private final TorrentMetadata meta;
    private final int maxPeers;

    PexManager(TorrentMetadata meta, int maxPeers) {
        this.meta = meta;
        this.maxPeers = maxPeers;
    }

    /**
     * 我们的 BEP 10 扩展握手载荷（声明 ut_pex 子 ID）；private 种子返回 null
     * （BEP 27：不协商）。
     */
    @Nullable
    ExtendedMessage ourHandshake() {
        if (meta.privateFlag()) {
            return null; // BEP 27：private 种子禁用 PEX（不做对等交换，仅 tracker 通道）
        }
        Map<BString, BencodeValue> m = new TreeMap<>(BString.UNSIGNED_ORDER);
        m.put(BString.of("ut_pex"), new BInteger(UT_PEX_ID));
        Map<BString, BencodeValue> handshake = new TreeMap<>(BString.UNSIGNED_ORDER);
        handshake.put(BString.of("m"), new BDict(m));
        return new ExtendedMessage(0, Bencode.encode(new BDict(handshake)));
    }

    /** BEP 10 扩展握手（对端 → 我们）：记录其 ut_pex 子 ID（有则开启 PEX 接收）。 */
    void onRemoteHandshake(PeerSession session, byte[] payload) {
        try {
            var value = Bencode.decodeValue(ByteBuffer.wrap(payload));
            if (value instanceof BDict dict
                    && dict.get("m") instanceof BDict m
                    && m.get("ut_pex") instanceof BInteger id) {
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
     * BEP 11 ut_pex：解析 added 紧凑表并把地址交给候选队列（连接上限内）。
     */
    void onPex(PeerSession session, byte[] payload, ConcurrentHashMap<String, PeerSession> peers,
            Consumer<InetSocketAddress> candidateSink) {
        if (meta.privateFlag()) {
            return; // BEP 27：private 种子不参与对等交换（我们也不广播，此处双保险）
        }
        try {
            var value = Bencode.decodeValue(ByteBuffer.wrap(payload));
            if (!(value instanceof BDict dict)
                    || !(dict.get("added") instanceof BString added)) {
                return;
            }
            byte[] data = added.value();
            if (data.length % 6 != 0) {
                return;
            }
            int introduced = 0;
            for (int i = 0; i + 6 <= data.length; i += 6) {
                // added.f 为可选字段（BEP 11），即使缺失也照常取地址；位图语义（加密等）我们不用
                if (peers.size() >= maxPeers) {
                    break;
                }
                candidateSink.accept(CompactPeer.decode6(data, i));
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
    void broadcast(ConcurrentHashMap<String, PeerSession> peers) {
        if (meta.privateFlag()) {
            return; // BEP 27：private 种子不广播连接表
        }
        ByteArrayOutputStream compact = new ByteArrayOutputStream();
        ByteArrayOutputStream flags = new ByteArrayOutputStream();
        int count = 0;
        for (PeerSession session : peers.values()) {
            byte[] entry = CompactPeer.encode6(session.channel.remoteAddress());
            if (entry == null) {
                continue; // 未解析地址或 IPv6（PEX 需 added6，暂不支持）
            }
            compact.write(entry, 0, entry.length);
            flags.write(0x00); // 无特殊语义位
            count++;
        }
        if (count == 0) {
            return;
        }
        Map<BString, BencodeValue> dict = new TreeMap<>(BString.UNSIGNED_ORDER);
        dict.put(BString.of("added"), new BString(compact.toByteArray()));
        dict.put(BString.of("added.f"), new BString(flags.toByteArray()));
        byte[] payload = Bencode.encode(new BDict(dict));
        int recipients = 0;
        for (PeerSession session : peers.values()) {
            if (session.remotePexId > 0) {
                try {
                    session.channel.write(
                            new ExtendedMessage(session.remotePexId, payload));
                    recipients++;
                } catch (RuntimeException ignored) {
                    // 通道关闭竞态：写失败由 close 路径收尾
                }
            }
        }
        log.debug("pex broadcast: {} peers listed, {} recipients", count, recipients);
    }
}
