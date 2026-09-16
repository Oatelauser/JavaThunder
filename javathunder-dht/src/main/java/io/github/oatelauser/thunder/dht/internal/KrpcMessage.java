package io.github.oatelauser.thunder.dht.internal;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * KRPC 报文（BEP 5）：bencoded 字典，字段 t=事务ID / y=类型(q,r,e) / q=方法名 /
 * a=参数 / r=响应 / e=错误列表。本类只做结构与线格式的双向映射，不含网络语义。
 */
public final class KrpcMessage {

    /**
     * 20 字节 DHT 节点 ID。
     */
    public record NodeId(byte[] bytes) {
        public NodeId {
            if (bytes.length != 20) {
                throw new IllegalArgumentException("node id must be 20 bytes");
            }
            bytes = bytes.clone();
        }

        /**
         * XOR 距离（Kademlia 度量），大端序比较。
         */
        public byte[] distanceTo(NodeId other) {
            byte[] out = new byte[20];
            for (int i = 0; i < 20; i++) {
                out[i] = (byte) (bytes[i] ^ other.bytes[i]);
            }
            return out;
        }

        public String hex() {
            return HexFormat.of().formatHex(bytes);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof NodeId other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    /**
     * 紧凑节点/对端地址（26 字节 = id+ip+port / 6 字节 = ip+port）。
     */
    public record PeerAddr(byte @Nullable [] nodeId, String host, int port) {

        public static PeerAddr compact6(byte[] six) {
            if (six.length != 6) {
                throw new IllegalArgumentException("compact peer must be 6 bytes");
            }
            String host = (six[0] & 0xFF) + "." + (six[1] & 0xFF) + "."
                    + (six[2] & 0xFF) + "." + (six[3] & 0xFF);
            int port = ((six[4] & 0xFF) << 8) | (six[5] & 0xFF);
            return new PeerAddr(null, host, port);
        }

        public static PeerAddr compact26(byte[] twentySix) {
            if (twentySix.length != 26) {
                throw new IllegalArgumentException("compact node must be 26 bytes");
            }
            PeerAddr base = compact6(Arrays.copyOfRange(twentySix, 20, 26));
            return new PeerAddr(Arrays.copyOf(twentySix, 20), base.host, base.port);
        }
    }

    public static final class Builder {

        private static Map<BString, BencodeValue> copyDict(BDict d) {
            Map<BString, BencodeValue> copy = new TreeMap<>(BString.UNSIGNED_ORDER);
            copy.putAll(d.value());
            return copy;
        }

        private final Map<BString, BencodeValue> dict = new TreeMap<>(BString.UNSIGNED_ORDER);
        private final byte[] transactionId;

        private Builder(byte[] transactionId, String type) {
            this.transactionId = transactionId.clone();
            dict.put(BString.of("t"), new BString(transactionId));
            dict.put(BString.of("y"), BString.of(type));
        }

        public static Builder query(byte[] transactionId, String method) {
            Builder builder = new Builder(transactionId, "q");
            builder.dict.put(BString.of("q"), BString.of(method));
            return builder;
        }

        public static Builder response(byte[] transactionId) {
            return new Builder(transactionId, "r");
        }

        public static Builder error(byte[] transactionId, int code, String message) {
            Builder builder = new Builder(transactionId, "e");
            builder.dict.put(BString.of("e"), new BList(List.of(
                    new BInteger(code), BString.of(message))));
            return builder;
        }

        public Builder arg(String key, BencodeValue value) {
            return section("a", key, value);
        }

        public Builder result(String key, BencodeValue value) {
            return section("r", key, value);
        }

        private Builder section(String section, String key, BencodeValue value) {
            BencodeValue existing = dict.get(BString.of(section));
            Map<BString, BencodeValue> map = existing instanceof BDict d
                    ? copyDict(d) : new TreeMap<>(BString.UNSIGNED_ORDER);
            map.put(BString.of(key), value);
            dict.put(BString.of(section), new BDict(map));
            return this;
        }

        public Builder id(NodeId id) {
            BString section = BString.of(dict.containsKey(BString.of("q")) ? "a" : "r");
            BencodeValue existing = dict.get(section);
            Map<BString, BencodeValue> map = existing instanceof BDict d
                    ? copyDict(d) : new TreeMap<>(BString.UNSIGNED_ORDER);
            map.put(BString.of("id"), new BString(id.bytes()));
            dict.put(section, new BDict(map));
            return this;
        }

        public byte[] encode() {
            return Bencode.encode(new BDict(dict));
        }

        /**
         * 本构建器将写入线格式的事务 ID（发送方据此登记/撤销事务配对，免二次解析报文）。
         */
        public byte[] transactionId() {
            return transactionId.clone();
        }
    }

    /**
     * 解析后的 KRPC 报文。
     */
    public record Parsed(byte[] transactionId, String type,
                         String method, @Nullable BDict args,
                         @Nullable BDict response,
                         @Nullable List<Object> error) {

        public NodeId nodeId() {
            BDict source = args != null ? args : response;
            if (source != null && source.get("id") instanceof BString id) {
                return new NodeId(id.value());
            }
            throw new IllegalArgumentException("krpc message without id");
        }

        public List<PeerAddr> nodes() {
            if (response == null || !(response.get("nodes") instanceof BString raw)) {
                return List.of();
            }
            byte[] data = raw.value();
            if (data.length % 26 != 0) {
                return List.of();
            }
            List<PeerAddr> result = new ArrayList<>(data.length / 26);
            for (int i = 0; i + 26 <= data.length; i += 26) {
                result.add(PeerAddr.compact26(Arrays.copyOfRange(data, i, i + 26)));
            }
            return result;
        }

        public List<PeerAddr> values() {
            if (response == null || !(response.get("values") instanceof BList list)) {
                return List.of();
            }
            List<PeerAddr> result = new ArrayList<>();
            for (BencodeValue value : list.value()) {
                if (value instanceof BString compact) {
                    try {
                        result.add(PeerAddr.compact6(compact.value()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return result;
        }

        public byte[] token() {
            return response != null && response.get("token") instanceof BString token
                    ? token.value() : null;
        }
    }

    public static Parsed parse(byte[] wire) {
        try {
            BencodeValue decoded = Bencode.decode(wire);
            if (!(decoded instanceof BDict dict)
                    || !(dict.get("t") instanceof BString t)
                    || !(dict.get("y") instanceof BString y)) {
                throw new IllegalArgumentException("krpc requires t and y");
            }
            String type = y.text();
            String method = dict.get("q") instanceof BString q ? q.text() : null;
            BDict args = dict.get("a") instanceof BDict a ? a : null;
            BDict response = dict.get("r") instanceof BDict r ? r : null;
            List<Object> error = null;
            if (dict.get("e") instanceof BList e && e.value().size() == 2
                    && e.value().get(0) instanceof BInteger code
                    && e.value().get(1) instanceof BString message) {
                error = List.of((int) code.value(), message.text());
            }
            return new Parsed(t.value(), type, method, args, response, error);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed krpc: " + e.getMessage(), e);
        }
    }

    public static InetSocketAddress resolve(PeerAddr addr) {
        return new InetSocketAddress(addr.host(), addr.port());
    }
}
