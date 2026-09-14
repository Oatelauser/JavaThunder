package io.github.oatelauser.thunder.core.internal.bencode;

/** Bencode 整数，语法 {@code i<digits>e}。 */
public record BInteger(long value) implements BencodeValue {
}
