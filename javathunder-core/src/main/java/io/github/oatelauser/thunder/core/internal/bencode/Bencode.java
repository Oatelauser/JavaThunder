package io.github.oatelauser.thunder.core.internal.bencode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bencode 编解码器（BEP 3）。
 *
 * <p>解码容忍未排序的字典键（现实世界的种子不总规范）；编码一律按键字典序输出规范形。
 * 防护：递归深度上限 {@value #MAX_DEPTH}、单串上限 {@value #MAX_STRING_BYTES} 字节、
 * 输入上限 {@value #MAX_INPUT_BYTES} 字节、长度声明超出剩余输入时在分配前拒绝。
 */
public final class Bencode {

    public static final int MAX_DEPTH = 64;
    public static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    public static final int MAX_INPUT_BYTES = 64 * 1024 * 1024;

    private Bencode() {
    }

    /** 解码单个顶层值，要求其后无残余字节。 */
    public static BencodeValue decode(byte[] data) {
        if (data.length > MAX_INPUT_BYTES) {
            throw new BencodeException("input exceeds " + MAX_INPUT_BYTES + " bytes: " + data.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        BencodeValue value = decodeValue(buf);
        if (buf.hasRemaining()) {
            throw new BencodeException("trailing data after top-level value at offset " + buf.position());
        }
        return value;
    }

    /**
     * 从缓冲区当前位置解码一个值，结束后位置停在该值之后。
     * 种子解析器依赖此语义捕获 info 字典的原始字节区间。
     */
    public static BencodeValue decodeValue(ByteBuffer buf) {
        return decode(buf, 0);
    }

    private static BencodeValue decode(ByteBuffer buf, int depth) {
        if (depth > MAX_DEPTH) {
            throw new BencodeException("nesting deeper than " + MAX_DEPTH);
        }
        if (!buf.hasRemaining()) {
            throw new BencodeException("unexpected end of input");
        }
        int tag = buf.get(buf.position()) & 0xFF;
        return switch (tag) {
            case 'i' -> {
                buf.get();
                yield decodeInteger(buf);
            }
            case 'l' -> {
                buf.get();
                yield decodeList(buf, depth);
            }
            case 'd' -> {
                buf.get();
                yield decodeDict(buf, depth);
            }
            default -> decodeString(buf);
        };
    }

    private static BInteger decodeInteger(ByteBuffer buf) {
        long magnitude = 0;
        boolean negative = false;
        boolean anyDigit = false;
        boolean leadingZero = false;
        if (buf.hasRemaining() && (buf.get(buf.position()) & 0xFF) == '-') {
            negative = true;
            buf.get();
        }
        while (buf.hasRemaining()) {
            int c = buf.get() & 0xFF;
            if (c == 'e') {
                if (!anyDigit) {
                    throw new BencodeException("integer without digits");
                }
                if (leadingZero) {
                    throw new BencodeException("integer with leading zero");
                }
                if (negative && magnitude == 0) {
                    throw new BencodeException("negative zero");
                }
                long value = negative ? -magnitude : magnitude;
                if (value < 0 && !negative) {
                    throw new BencodeException("integer overflow");
                }
                return new BInteger(value);
            }
            if (c < '0' || c > '9') {
                throw new BencodeException("non-digit in integer body");
            }
            if (anyDigit && magnitude == 0) {
                leadingZero = true;
            }
            anyDigit = true;
            long digit = c - '0';
            if (magnitude > (Long.MAX_VALUE - digit) / 10) {
                throw new BencodeException("integer overflow");
            }
            magnitude = magnitude * 10 + digit;
        }
        throw new BencodeException("integer not terminated by 'e'");
    }

    private static BString decodeString(ByteBuffer buf) {
        long length = 0;
        boolean anyDigit = false;
        boolean leadingZero = false;
        while (buf.hasRemaining()) {
            int c = buf.get() & 0xFF;
            if (c == ':') {
                if (!anyDigit) {
                    throw new BencodeException("string without length");
                }
                if (leadingZero) {
                    throw new BencodeException("string length with leading zero");
                }
                if (length > MAX_STRING_BYTES) {
                    throw new BencodeException("string exceeds " + MAX_STRING_BYTES + " bytes: " + length);
                }
                if (length > buf.remaining()) {
                    throw new BencodeException("string truncated: declared " + length
                        + ", available " + buf.remaining());
                }
                byte[] data = new byte[(int) length];
                buf.get(data);
                return new BString(data);
            }
            if (c < '0' || c > '9') {
                throw new BencodeException("non-digit in string length");
            }
            if (anyDigit && length == 0) {
                leadingZero = true;
            }
            anyDigit = true;
            length = length * 10 + (c - '0');
            if (length > MAX_STRING_BYTES) {
                throw new BencodeException("string exceeds " + MAX_STRING_BYTES + " bytes: " + length);
            }
        }
        throw new BencodeException("string length not terminated by ':'");
    }

    private static BList decodeList(ByteBuffer buf, int depth) {
        List<BencodeValue> values = new ArrayList<>();
        while (true) {
            if (!buf.hasRemaining()) {
                throw new BencodeException("list not terminated by 'e'");
            }
            if ((buf.get(buf.position()) & 0xFF) == 'e') {
                buf.get();
                return new BList(values);
            }
            values.add(decode(buf, depth + 1));
        }
    }

    private static BDict decodeDict(ByteBuffer buf, int depth) {
        TreeMapBuilder builder = new TreeMapBuilder();
        while (true) {
            if (!buf.hasRemaining()) {
                throw new BencodeException("dict not terminated by 'e'");
            }
            if ((buf.get(buf.position()) & 0xFF) == 'e') {
                buf.get();
                return builder.build();
            }
            int tag = buf.get(buf.position()) & 0xFF;
            if (tag < '0' || tag > '9') {
                throw new BencodeException("dict key must be a byte string");
            }
            BString key = decodeString(buf);
            if (!buf.hasRemaining()) {
                throw new BencodeException("dict value missing for key at offset " + (buf.position() - 1));
            }
            builder.put(key, decode(buf, depth + 1));
        }
    }

    private static final class TreeMapBuilder {
        private final TreeMap<BString, BencodeValue> map = new TreeMap<>(BString.UNSIGNED_ORDER);

        void put(BString key, BencodeValue value) {
            map.put(key, value);
        }

        BDict build() {
            return new BDict(map);
        }
    }

    /** 输出规范形编码（字典键按无符号字典序）。 */
    public static byte[] encode(BencodeValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        write(value, out, 0);
        return out.toByteArray();
    }

    private static void write(BencodeValue value, ByteArrayOutputStream out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new BencodeException("nesting deeper than " + MAX_DEPTH);
        }
        switch (value) {
            case BInteger i -> out.writeBytes(("i" + i.value() + "e").getBytes(StandardCharsets.US_ASCII));
            case BString s -> {
                out.writeBytes((s.value().length + ":").getBytes(StandardCharsets.US_ASCII));
                out.writeBytes(s.value());
            }
            case BList l -> {
                out.write('l');
                for (BencodeValue item : l.value()) {
                    write(item, out, depth + 1);
                }
                out.write('e');
            }
            case BDict d -> {
                out.write('d');
                for (Map.Entry<BString, BencodeValue> entry : d.value().entrySet()) {
                    write(entry.getKey(), out, depth + 1);
                    write(entry.getValue(), out, depth + 1);
                }
                out.write('e');
            }
        }
    }
}
