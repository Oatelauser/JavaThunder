package io.github.oatelauser.thunder.core.internal.engine;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在内存中按块槽位零拷贝组装：解码块直接挂引用，齐件后顺序喂摘要 + gather 落盘。
 */
final class PieceAssembler {

    final byte[][] blocks;
    final int pieceLength;
    final int[] blockLengths;
    final int expectedBlocks;
    final Set<BlockRequest> received = ConcurrentHashMap.newKeySet();

    PieceAssembler(int pieceLength, List<BlockRequest> blocksOfPiece) {
        this.pieceLength = pieceLength;
        this.expectedBlocks = blocksOfPiece.size();
        this.blocks = new byte[expectedBlocks][];
        this.blockLengths = new int[expectedBlocks];
        for (BlockRequest block : blocksOfPiece) {
            int slot = block.begin() / PieceScheduler.BLOCK_SIZE;
            blockLengths[slot] = block.length();
        }
    }

    /**
     * 槽位校验：begin/length 必须与该槽的请求对齐（协议上对端只回我们请求过的块）。
     */
    int slotOf(int begin, int length) {
        if (begin % PieceScheduler.BLOCK_SIZE != 0) {
            return -1;
        }
        int slot = begin / PieceScheduler.BLOCK_SIZE;
        if (slot >= blockLengths.length || blockLengths[slot] != length) {
            return -1;
        }
        return slot;
    }
}
