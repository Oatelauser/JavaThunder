package io.github.oatelauser.thunder.core.internal.bencode;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bencode 字典，语法 {@code d<key><value>...e}。
 * 内容始终以 {@link BString#UNSIGNED_ORDER} 排序保存，编码输出即规范形。
 */
public record BDict(Map<BString, BencodeValue> value) implements BencodeValue {

    public BDict {
        TreeMap<BString, BencodeValue> sorted = new TreeMap<>(BString.UNSIGNED_ORDER);
        sorted.putAll(value);
        value = Collections.unmodifiableSortedMap(sorted);
    }

    public static BDict of(Map<BString, BencodeValue> entries) {
        return new BDict(entries);
    }

    public BencodeValue get(String key) {
        return value.get(BString.of(key));
    }
}
