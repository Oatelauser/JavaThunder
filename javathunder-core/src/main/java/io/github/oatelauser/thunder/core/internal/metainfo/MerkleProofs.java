package io.github.oatelauser.thunder.core.internal.metainfo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * BEP 52 哈希交换的 Merkle 证明数学（纯函数，供种应答与客户端校验共用）。
 *
 * <p>层坐标：base=0 为 16KiB 块叶子层；文件树叶子数补齐到 2 的幂（填充叶 = 32 字节
 * 零哈希，逐层折叠成零链常数），根位于 {@code H = log2(nextPow2(blocks))}。
 * piece layer 位于 {@code log2(blocksPerPiece)}。一个 {@code [index, index+count)}
 * 的对齐 2 的幂区间自身折叠成子树根，其后沿 uncle 链（每层一个兄弟哈希）爬到根即
 * 构成完整证明——证明哈希个数 = {@code proofLayers - log2(count) + 1}（与 libtorrent
 * 收发两侧同一公式；区间覆盖整层时为 0）。
 */
public final class MerkleProofs {

    /** libtorrent 对单请求 count 的上限（对应 128MB piece 的块哈希请求）。 */
    public static final int MAX_CHUNK = 8192;

    private MerkleProofs() {
    }

    /** 2 的幂取 log2（参数非法返回值无意义，调用方保证 pow2）。 */
    public static int log2(int powerOfTwo) {
        return Integer.numberOfTrailingZeros(powerOfTwo);
    }

    /** 向上取整到 2 的幂（n ≤ 1 返回 1）。 */
    public static int nextPow2(int n) {
        if (n <= 1) {
            return 1;
        }
        return Integer.highestOneBit(n - 1) << 1;
    }

    /**
     * 填充子树哈希（零链）：叶子层上方 {@code layersAboveLeaf} 层处、覆盖 2^层 数个
     * 零填充叶的节点值。委托 {@link MerkleHashes#padHash}（单一数学来源）。
     */
    public static byte[] padNode(int layersAboveLeaf) {
        return MerkleHashes.padHash(layersAboveLeaf);
    }

    /**
     * 客户端校验一个 hashes 应答：count 个层哈希折叠成子树根，沿 proof 的 uncle 链
     * 爬 {@code treeHeight - baseLayer - log2(count)} 层后必须等于 pieces root。
     * 证明个数不符 / count 非 2 的幂 / index 未对齐均判失败（调用方换 Peer 重试）。
     */
    public static boolean verifyChunk(byte[] piecesRoot, int baseLayer, int index,
            List<byte[]> hashes, List<byte[]> proof, int treeHeight) {
        int count = hashes.size();
        if (count <= 0 || Integer.bitCount(count) != 1 || index < 0 || index % count != 0) {
            return false;
        }
        int expectedProofs = treeHeight - baseLayer - log2(count);
        if (expectedProofs < 0 || proof.size() != expectedProofs) {
            return false;
        }
        byte[] current = foldAll(hashes);
        int pos = index >> log2(count); // 子树根在其层的位次
        for (byte[] uncle : proof) {
            current = (pos & 1) == 0 ? sha256(current, uncle) : sha256(uncle, current);
            pos >>= 1;
        }
        return MessageDigest.isEqual(current, piecesRoot);
    }

    /**
     * 供种应答：从 piece-layer 哈希带 {@code strip}（{@code filePieces} 个真实哈希）
     * 构造 [index, index+count) 区间的层哈希（越尾部分为零链填充）与到根的 uncle 链。
     * 只服务 base == pieceLayer 的请求；形状不合法（count 非 2 的幂 / 未对齐 / 越界 /
     * proofLayers 超过到根层数）返回 null——调用方应答 hash reject。
     */
    public static @Nullable Chunk buildChunk(byte[] strip, int filePieces, int pieceLayer,
            int baseLayer, int index, int count, int proofLayers) {
        int paddedWidth = nextPow2(filePieces);
        int height = log2(paddedWidth) + pieceLayer;
        if (baseLayer != pieceLayer || filePieces <= 1 || count <= 0 || count > MAX_CHUNK
                || Integer.bitCount(count) != 1 || index < 0 || index % count != 0
                || proofLayers < 0 || proofLayers > height - baseLayer - 1
                || index + count > paddedWidth) {
            return null;
        }
        List<byte[]> hashes = new ArrayList<>(count);
        for (int i = index; i < index + count; i++) {
            hashes.add(i < filePieces ? slice(strip, i) : padNode(pieceLayer));
        }
        List<byte[]> proof = new ArrayList<>(proofLayers - log2(count) + 1);
        appendProofs(proof, strip, filePieces, pieceLayer, index, count, proofLayers);
        return new Chunk(hashes, proof);
    }

    /** 一个应答区间：层哈希 + uncle 证明链。 */
    public record Chunk(List<byte[]> hashes, List<byte[]> proof) {
        public Chunk {
            hashes = List.copyOf(hashes);
            proof = List.copyOf(proof);
        }
    }

    /** uncle 链：第 k 个 = 子树根上方第 k 层的兄弟节点（由填充后的 base 层区间折叠）。 */
    private static void appendProofs(List<byte[]> out, byte[] strip, int filePieces,
            int pieceLayer, int index, int count, int proofLayers) {
        int subtreeTop = log2(count);
        int pos = index >> subtreeTop;
        int proofCount = proofLayers - subtreeTop + 1;
        for (int k = 0; k < proofCount; k++) {
            long sibling = ((long) pos >> k) ^ 1L;
            out.add(subtreeRoot(strip, filePieces, pieceLayer,
                    sibling << (subtreeTop + k), 1 << (subtreeTop + k)));
        }
    }

    /** 填充后 base 层上 [start, start+width) 区间（2 的幂宽）折叠出的上层节点。 */
    private static byte[] subtreeRoot(byte[] strip, int filePieces, int pieceLayer,
            long start, int width) {
        List<byte[]> level = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            long pos = start + i;
            level.add(pos < filePieces
                    ? slice(strip, (int) pos)
                    : padNode(pieceLayer));
        }
        return foldAll(level);
    }

    private static byte[] slice(byte[] strip, int pieceIndex) {
        byte[] hash = new byte[MerkleHashes.HASH_WIDTH];
        System.arraycopy(strip, pieceIndex * MerkleHashes.HASH_WIDTH,
                hash, 0, MerkleHashes.HASH_WIDTH);
        return hash;
    }

    /** 2 的幂宽列表的纯两两折叠（无零填充——宽度已保证）。 */
    private static byte[] foldAll(List<byte[]> level) {
        List<byte[]> current = level;
        while (current.size() > 1) {
            List<byte[]> next = new ArrayList<>(current.size() / 2);
            for (int i = 0; i < current.size(); i += 2) {
                next.add(sha256(current.get(i), current.get(i + 1)));
            }
            current = next;
        }
        return current.get(0);
    }

    private static byte[] sha256(byte[] left, byte[] right) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(left);
            digest.update(right);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }
}
