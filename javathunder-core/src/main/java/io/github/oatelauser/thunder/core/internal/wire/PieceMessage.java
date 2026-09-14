package io.github.oatelauser.thunder.core.internal.wire;

import java.util.Arrays;

/** 一个 Block 的数据（ID 7，载荷 = piece 序号 + 块内偏移 + 原始字节）。 */
public record PieceMessage(int pieceIndex, int begin, byte[] block) implements PeerWireMessage {

    public PieceMessage {
        block = block.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PieceMessage other
            && pieceIndex == other.pieceIndex && begin == other.begin
            && Arrays.equals(block, other.block);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(pieceIndex);
        result = 31 * result + Integer.hashCode(begin);
        return 31 * result + Arrays.hashCode(block);
    }
}
