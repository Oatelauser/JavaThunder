package io.github.oatelauser.thunder.core.internal.bencode;

import java.util.List;

/** Bencode 列表，语法 {@code l<values>e}。 */
public record BList(List<BencodeValue> value) implements BencodeValue {

    public BList {
        value = List.copyOf(value);
    }
}
