package io.github.oatelauser.thunder.core.internal.bencode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** Bencode 字节串，语法 {@code <len>:<raw bytes>}；相等性按内容而非引用。 */
public record BString(byte[] value) implements BencodeValue {

    /** 无符号字典序：规范形编码时字典键的排序规则。 */
    public static final Comparator<BString> UNSIGNED_ORDER =
        Comparator.comparing(BString::toUnsigned, Arrays::compareUnsigned);

    private static byte[] toUnsigned(BString s) {
        return s.value;
    }

    public static BString of(String text) {
        return new BString(text.getBytes(StandardCharsets.UTF_8));
    }

    public String text() {
        return new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BString other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }
}
