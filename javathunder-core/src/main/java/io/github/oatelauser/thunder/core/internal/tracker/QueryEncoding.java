package io.github.oatelauser.thunder.core.internal.tracker;

/**
 * Tracker 参数的逐字节百分号编码。
 *
 * <p>info_hash / peer_id 是任意二进制字节，标准 URL 编码器（会做字符集转换）不可用；
 * 仅 RFC 3986 非保留字符 [A-Za-z0-9.-_~] 原样输出，其余字节一律 %XX 大写十六进制。
 */
public final class QueryEncoding {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private QueryEncoding() {
    }

    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 3);
        for (byte b : data) {
            int c = b & 0xFF;
            if (isUnreserved(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >>> 4]).append(HEX[c & 0xF]);
            }
        }
        return out.toString();
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
