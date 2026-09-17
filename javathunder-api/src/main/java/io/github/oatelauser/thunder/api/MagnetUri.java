package io.github.oatelauser.thunder.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * 磁力链接（BEP 9 / BEP 52）：{@code magnet:?xt=urn:btih:<info-hash>&dn=<名称>&tr=<tracker>}。
 * v1 info-hash 支持 40 位 hex 或 32 位 base32；v2 支持 {@code xt=urn:btmh:1220<64 hex>}
 * （multihash：sha2-256），混合磁力可同时携带两种 xt。tr 可多个。xt 缺失或哈希非法则拒绝。
 * 元数据由连接到的 Peer 通过 ut_metadata 拉取（B1）。
 *
 * <p>v2 语义：{@code infoHashV2} 为完整 32 字节哈希（仅 btmh 存在时非空）；主
 * {@code infoHash} 恒 20 字节——v2-only 磁力取其前 20 字节截断（DHT 查找目标口径）。
 */
public record MagnetUri(byte[] infoHash, String displayName, List<String> trackers,
        @Nullable byte[] infoHashV2) {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String BTMH_PREFIX = "urn:btmh:1220";

    /** v1 形态兼容构造（无 v2 哈希）。 */
    public MagnetUri(byte[] infoHash, String displayName, List<String> trackers) {
        this(infoHash, displayName, trackers, null);
    }

    public MagnetUri {
        if (infoHash == null || infoHash.length != 20) {
            throw new IllegalArgumentException("magnet info-hash must be 20 bytes");
        }
        if (infoHashV2 != null && infoHashV2.length != 32) {
            throw new IllegalArgumentException("magnet v2 info-hash must be 32 bytes");
        }
        infoHash = infoHash.clone();
        infoHashV2 = infoHashV2 == null ? null : infoHashV2.clone();
        trackers = List.copyOf(trackers);
    }

    public static MagnetUri parse(String uri) {
        if (uri == null || !uri.startsWith("magnet:?")) {
            throw new IllegalArgumentException("not a magnet uri: " + uri);
        }
        byte[] infoHash = null;
        byte[] infoHashV2 = null;
        String displayName = "";
        List<String> trackers = new ArrayList<>();
        for (String pair : uri.substring("magnet:?".length()).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            String value = urlDecode(pair.substring(eq + 1));
            switch (key) {
                case "xt" -> {
                    String urn = value.toLowerCase();
                    if (urn.startsWith("urn:btih:")) {
                        infoHash = decodeInfoHash(urn.substring("urn:btih:".length()));
                    } else if (urn.startsWith(BTMH_PREFIX)) {
                        String encoded = urn.substring(BTMH_PREFIX.length());
                        if (encoded.length() != 64) {
                            throw new IllegalArgumentException("btmh must be 64 hex chars: " + value);
                        }
                        infoHashV2 = HexFormat.of().parseHex(encoded);
                        if (infoHash == null) {
                            // v2-only：主哈希取截断 20 字节（DHT/线协议的 v1 宽度口径）
                            infoHash = Arrays.copyOf(infoHashV2, 20);
                        }
                    } else {
                        throw new IllegalArgumentException("unsupported xt urn: " + value);
                    }
                }
                case "dn" -> displayName = value;
                case "tr" -> trackers.add(value);
                default -> {
                }
            }
        }
        if (infoHash == null) {
            throw new IllegalArgumentException("magnet uri missing xt (info-hash)");
        }
        return new MagnetUri(infoHash, displayName, trackers, infoHashV2);
    }

    private static byte[] decodeInfoHash(String encoded) {
        if (encoded.length() == 40) {
            return HexFormat.of().parseHex(encoded);
        }
        if (encoded.length() == 32) {
            return base32Decode(encoded.toUpperCase());
        }
        throw new IllegalArgumentException("btih must be 40 hex or 32 base32 chars: " + encoded);
    }


    /**
     * RFC 4648 base32 解码（无填充容忍）。
     */
    static byte[] base32Decode(String input) {
        StringBuilder cleaned = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c != '=') {
                cleaned.append(c);
            }
        }
        int length = cleaned.length();
        if (length % 8 != 0 && length % 8 != 2 && length % 8 != 4 && length % 8 != 5 && length % 8 != 7) {
            throw new IllegalArgumentException("invalid base32 length: " + length);
        }
        List<Byte> out = getOut(length, cleaned);
        byte[] result = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    private static List<Byte> getOut(int length, StringBuilder cleaned) {
        long buffer = 0;
        int bits = 0;
        List<Byte> out = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int value = BASE32_ALPHABET.indexOf(cleaned.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("invalid base32 char: " + cleaned.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.add((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

}
