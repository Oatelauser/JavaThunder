package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.metainfo.MerkleHashes;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.storage.TorrentStorage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * V2 逐件校验与落盘（BEP 52）：件数据按 16KiB 拆块 → SHA-256 叶子哈希 → 补零到
 * blocksPerPiece 宽 → Merkle 折叠 → 与文件层带条目比对。件属于哪个文件由全局偏移
 * 定位（v2 文件件对齐，一件恒属一文件）。
 */
final class V2PieceVerifier {

    private V2PieceVerifier() {
    }

    /**
     * @return 校验通过且已落盘 true；哈希不符 false（不落盘）。单 piece 文件无层带时
     *         直接对 pieces root 比对整件哈希。
     * @throws IOException 落盘 I/O 失败（调用方据此失败任务）
     */
    static boolean verifyAndStore(TorrentStorage storage, TorrentMetadata meta,
            PieceAssembler assembler, int piece) throws IOException {
        byte[] pieceData = flatten(assembler);
        if (!verify(meta, piece, pieceData)) {
            return false;
        }
        ByteBuffer[] buffers = new ByteBuffer[assembler.blocks.length];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = ByteBuffer.wrap(assembler.blocks[i]);
        }
        storage.writePieceBuffers(piece, buffers);
        return true;
    }

    /** 纯校验（不落盘）：供 WebSeed 等需要先验后写的路径复用。 */
    static boolean verify(TorrentMetadata meta, int piece, byte[] pieceData) {
        TorrentMetadata.TorrentFile file = fileForPiece(meta, piece);
        if (file == null) {
            return false;
        }
        long pieceLength = meta.pieceLength();
        int localPiece = (int) ((piece * pieceLength - file.offset()) / pieceLength);

        // 叶子哈希：按 16KiB 拆块（末块按剩余长度截断）
        int blocksPerPiece = (int) (pieceLength / 16384);
        List<byte[]> leaves = new ArrayList<>(blocksPerPiece);
        int actualBlocks = (pieceData.length + 16383) / 16384;
        for (int b = 0; b < actualBlocks; b++) {
            int from = b * 16384;
            int to = Math.min(from + 16384, pieceData.length);
            leaves.add(sha256(Arrays.copyOfRange(pieceData, from, to)));
        }
        // 补零到 blocksPerPiece 宽（对齐子树形状）
        while (leaves.size() < blocksPerPiece) {
            leaves.add(MerkleHashes.ZERO_HASH);
        }
        byte[] subtreeRoot = MerkleHashes.rootOfLayer(leaves);

        // 比对：多 piece 文件取层带条目；单 piece 文件直接对 pieces root
        byte[] layer = file.pieceLayer();
        if (layer != null && layer.length >= (localPiece + 1) * MerkleHashes.HASH_WIDTH) {
            byte[] expected = Arrays.copyOfRange(layer,
                    localPiece * MerkleHashes.HASH_WIDTH, (localPiece + 1) * MerkleHashes.HASH_WIDTH);
            return MessageDigest.isEqual(subtreeRoot, expected);
        }
        if (file.piecesRoot() != null) {
            return MessageDigest.isEqual(subtreeRoot, file.piecesRoot());
        }
        return false;
    }

    /** 定位包含该全局件起点的文件（v2 文件件对齐，一件恒属一文件）。 */
    private static TorrentMetadata.TorrentFile fileForPiece(TorrentMetadata meta, int piece) {
        long pieceStart = (long) piece * meta.pieceLength();
        for (TorrentMetadata.TorrentFile file : meta.files()) {
            if (pieceStart >= file.offset() && pieceStart < file.offset() + file.length()) {
                return file;
            }
        }
        // 起点恰在最后文件末尾之外（末件截断场景防御）
        return null;
    }

    private static byte[] flatten(PieceAssembler assembler) {
        int total = 0;
        for (byte[] block : assembler.blocks) {
            total += block.length;
        }
        byte[] data = new byte[total];
        int off = 0;
        for (byte[] block : assembler.blocks) {
            System.arraycopy(block, 0, data, off, block.length);
            off += block.length;
        }
        return data;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }
}
