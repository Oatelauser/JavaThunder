package io.github.oatelauser.thunder.dht.internal;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Builder;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.NodeId;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Parsed;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * KRPC 服务侧（BEP 5 全功能节点的应答面）：响应他人的 ping / find_node / get_peers /
 * announce_peer 查询——网络公民义务（DHT 健壮性来自节点互相应答），也让本节点的
 * 路由表随他人查询活跃度保持新鲜。
 *
 * <p><b>peer 索引</b>：info-hash → 已向我们 announce 的对端（TTL 30 分钟，BEP 5
 * 建议值；读时惰性剔除过期项）。get_peers 命中时作为 values 返回，否则返回最近
 * 节点让查询方继续逼近——与规范的两种应答形态一致。
 *
 * <p><b>token（防伪造 announce）</b>：无状态方案——token = SHA-256(进程密钥 ‖ 来源
 * IP ‖ 时段号) 前 8 字节，每 5 分钟轮换，校验接受当前与上一时段（时钟边界容忍）。
 * 不落库即可验证，天然免疫重启丢失；攻击者拿不到进程密钥则无法为他人 IP 伪造。
 * 密钥进程内随机（SecureRandom），非持久化——重启后旧 token 全部失效，属可接受
 * 的安全收紧。
 */
final class KrpcServer {

    private static final Logger log = LoggerFactory.getLogger(KrpcServer.class);
    /** token 轮换周期：校验窗口 = 当前 + 上一时段。 */
    static final long TOKEN_ROTATE_MILLIS = 5 * 60 * 1000;
    /** announce 存活期（BEP 5 建议不小于 30 分钟）。 */
    static final long ANNOUNCE_TTL_MILLIS = 30 * 60 * 1000;
    /** 单次 find_node/get_peers 响应回送的最近节点数（= K 桶宽）。 */
    private static final int CLOSEST_K = 8;
    /** values 回送上限：控应答报文尺寸（20 × 6B 已足够查询方一次建连）。 */
    private static final int VALUES_CAP = 20;
    private static final int ERROR_PROTOCOL = 203;
    private static final int ERROR_METHOD_UNKNOWN = 204;

    private final NodeId selfId;
    private final RoutingTable table;
    private final KrpcRpc rpc;
    private final LongSupplier clock;
    private final byte[] tokenSecret = new byte[16];
    /** info-hash hex → (对端 host:port → 过期时刻)；读路径惰性剔除。 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> announcedPeers =
            new ConcurrentHashMap<>();

    KrpcServer(NodeId selfId, RoutingTable table, KrpcRpc rpc, LongSupplier clock) {
        this.selfId = selfId;
        this.table = table;
        this.rpc = rpc;
        this.clock = clock;
        new SecureRandom().nextBytes(tokenSecret);
        rpc.onQuery(this::handle);
    }

    /**
     * 查询入口（KrpcRpc 接收线程回调）：登记查询方（活跃信号）→ 按方法分发 →
     * 原路回包。畸形参数回 203（Protocol Error），未知方法回 204——两者都是 BEP 5
     * 规定的错误码，显式回错优于静默丢弃（调用方可快速失败换节点）。
     */
    private void handle(InetSocketAddress source, Parsed query) {
        NodeId sender = nullableSenderId(query);
        if (sender != null) {
            table.offer(sender, source.getAddress().getHostAddress(), source.getPort());
        }
        Builder reply;
        try {
            reply = switch (query.method() == null ? "" : query.method()) {
                case "ping" -> Builder.response(query.transactionId()).id(selfId);
                case "find_node" -> findNodeReply(query);
                case "get_peers" -> getPeersReply(source, query);
                case "announce_peer" -> announceReply(source, query);
                default -> Builder.error(query.transactionId(), ERROR_METHOD_UNKNOWN,
                        "Method Unknown");
            };
        } catch (RuntimeException e) {
            // 单条畸形查询不得炸穿接收线程（KrpcRpc 只捕 IOException）——记日志静默丢弃
            log.debug("dropping malformed krpc query from {}: {}", source, e.toString());
            return;
        }
        rpc.sendQuery(source, reply);
    }

    private Builder findNodeReply(Parsed query) {
        byte[] target = argBytes(query.args(), "target");
        if (!isNodeId(target)) {
            return protocolError(query);
        }
        return Builder.response(query.transactionId())
                .id(selfId)
                .result("nodes", new BString(compactNodes(new NodeId(target))));
    }

    private Builder getPeersReply(InetSocketAddress source, Parsed query) {
        byte[] infoHash = argBytes(query.args(), "info_hash");
        if (!isNodeId(infoHash)) {
            return protocolError(query);
        }
        Builder reply = Builder.response(query.transactionId())
                .id(selfId)
                .result("token", new BString(tokenFor(source, currentBucket())))
                .result("nodes", new BString(compactNodes(new NodeId(infoHash))));
        List<byte[]> values = liveValues(infoHash);
        if (!values.isEmpty()) {
            List<BencodeValue> compact = new ArrayList<>(values.size());
            for (byte[] value : values) {
                compact.add(new BString(value));
            }
            reply.result("values", new BList(compact));
        }
        return reply;
    }

    private Builder announceReply(InetSocketAddress source, Parsed query) {
        byte[] infoHash = argBytes(query.args(), "info_hash");
        byte[] token = argBytes(query.args(), "token");
        if (!isNodeId(infoHash) || token == null) {
            return protocolError(query);
        }
        if (!tokenValid(source, token)) {
            return Builder.error(query.transactionId(), ERROR_PROTOCOL,
                    "announce_peer token invalid");
        }
        // implied_port=1（BEP 5）：端口以 UDP 包源端口为准——NAT 穿透场景下announce
        // 方自报的端口往往未经映射，源端口才对得上
        int port = argInt(query.args(), "implied_port") == 1
                ? source.getPort()
                : argInt(query.args(), "port");
        if (port <= 0 || port > 65535) {
            return protocolError(query);
        }
        announcedPeers.computeIfAbsent(HexFormat.of().formatHex(infoHash),
                        key -> new ConcurrentHashMap<>())
                .put(source.getAddress().getHostAddress() + ":" + port,
                        clock.getAsLong() + ANNOUNCE_TTL_MILLIS);
        return Builder.response(query.transactionId()).id(selfId);
    }

    private Builder protocolError(Parsed query) {
        return Builder.error(query.transactionId(), ERROR_PROTOCOL, "Protocol Error");
    }

    /** 索引读取：剔除过期项后返回前 {@value #VALUES_CAP} 个紧凑地址。 */
    private List<byte[]> liveValues(byte[] infoHash) {
        ConcurrentHashMap<String, Long> peers =
                announcedPeers.get(HexFormat.of().formatHex(infoHash));
        if (peers == null) {
            return List.of();
        }
        long now = clock.getAsLong();
        List<byte[]> values = new ArrayList<>();
        for (Map.Entry<String, Long> entry : peers.entrySet()) {
            if (entry.getValue() <= now) {
                peers.remove(entry.getKey());
                continue;
            }
            byte[] compact = compact6(entry.getKey());
            if (compact != null && values.size() < VALUES_CAP) {
                values.add(compact);
            }
        }
        return values;
    }

    /** 距 target 最近的 IPv4 节点编成紧凑 nodes（26B/节点；非 IPv4 主机跳过——BEP 32 超范围）。 */
    private byte[] compactNodes(NodeId target) {
        List<RoutingTable.Entry> nearest = table.nearest(target, CLOSEST_K * 2);
        byte[] out = new byte[nearest.size() * KrpcMessage.COMPACT_NODE_BYTES];
        int count = 0;
        for (RoutingTable.Entry entry : nearest) {
            byte[] address = ipv4Bytes(entry.host());
            if (address == null || count >= CLOSEST_K) {
                continue;
            }
            System.arraycopy(entry.id().bytes(), 0, out,
                    count * KrpcMessage.COMPACT_NODE_BYTES, KrpcMessage.NODE_ID_BYTES);
            System.arraycopy(address, 0, out,
                    count * KrpcMessage.COMPACT_NODE_BYTES + KrpcMessage.NODE_ID_BYTES, 4);
            int offset = count * KrpcMessage.COMPACT_NODE_BYTES
                    + KrpcMessage.NODE_ID_BYTES + 4;
            out[offset] = (byte) (entry.port() >> 8);
            out[offset + 1] = (byte) entry.port();
            count++;
        }
        return count == nearest.size() ? out
                : Arrays.copyOf(out, count * KrpcMessage.COMPACT_NODE_BYTES);
    }

    private static @Nullable byte[] compact6(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        byte[] address = ipv4Bytes(hostPort.substring(0, colon));
        if (address == null) {
            return null;
        }
        int port = Integer.parseInt(hostPort.substring(colon + 1));
        return new byte[]{address[0], address[1], address[2], address[3],
                (byte) (port >> 8), (byte) port};
    }

    /** 点分 IPv4 → 4 字节；其余（IPv6/主机名）返回 null——紧凑编码仅定义 IPv4。 */
    private static @Nullable byte[] ipv4Bytes(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int octet = Integer.parseInt(parts[i]);
                if (octet < 0 || octet > 255 || !parts[i].equals(String.valueOf(octet))) {
                    return null;
                }
                out[i] = (byte) octet;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- token

    private long currentBucket() {
        return clock.getAsLong() / TOKEN_ROTATE_MILLIS;
    }

    private byte[] tokenFor(InetSocketAddress source, long bucket) {
        MessageDigest digest = sha256();
        digest.update(tokenSecret);
        digest.update(source.getAddress().getHostAddress().getBytes(StandardCharsets.UTF_8));
        digest.update(longBytes(bucket));
        byte[] full = digest.digest();
        byte[] token = new byte[8];
        System.arraycopy(full, 0, token, 0, token.length);
        return token;
    }

    private boolean tokenValid(InetSocketAddress source, byte[] token) {
        long bucket = currentBucket();
        // 上一时段也接受：5 分钟轮换边界两侧的 in-flight announce 不应被误拒
        return MessageDigest.isEqual(tokenFor(source, bucket), token)
                || MessageDigest.isEqual(tokenFor(source, bucket - 1), token);
    }

    private static byte[] longBytes(long value) {
        return new byte[]{(byte) (value >> 56), (byte) (value >> 48), (byte) (value >> 40),
                (byte) (value >> 32), (byte) (value >> 24), (byte) (value >> 16),
                (byte) (value >> 8), (byte) value};
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }

    // ---------------------------------------------------------------- 参数提取

    /** 查询方 id（可缺省——应答不强制依赖它，但登记路由表需要）。 */
    private static @Nullable NodeId nullableSenderId(Parsed query) {
        if (query.args() == null || !(query.args().get("id") instanceof BString id)
                || id.value().length != KrpcMessage.NODE_ID_BYTES) {
            return null;
        }
        return new NodeId(id.value());
    }

    /** 取定长字节参数（target/info_hash 恒 20B，token 任意长非空）。 */
    private static @Nullable byte[] argBytes(@Nullable BDict args, String key) {
        if (args == null || !(args.get(key) instanceof BString value) || value.value().length == 0) {
            return null;
        }
        return value.value();
    }

    private static boolean isNodeId(@Nullable byte[] bytes) {
        return bytes != null && bytes.length == KrpcMessage.NODE_ID_BYTES;
    }

    private static int argInt(@Nullable BDict args, String key) {
        return args != null && args.get(key) instanceof BInteger value
                ? (int) value.value() : 0;
    }
}
