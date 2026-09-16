package io.github.oatelauser.thunder.core.internal.bencode;

/**
 * Bencode 数据畸形（违反 BEP 3 编码规则、超出防护上限）。
 */
public class BencodeException extends RuntimeException {

    public BencodeException(String message) {
        super(message);
    }
}
