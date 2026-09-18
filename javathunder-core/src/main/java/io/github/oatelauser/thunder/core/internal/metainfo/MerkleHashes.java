package io.github.oatelauser.thunder.core.internal.metainfo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * BEP 52 Merkle 哈希树数学（纯函数）：叶子恒为 16KiB 块的 SHA-256；父 = SHA-256(左‖右)。
 *
 * <p>填充约定（与 libtorrent 一致）：pad₀ = 32 字节零哈希，padₖ = SHA-256(padₖ₋₁‖padₖ₋₁)；
 * 折叠某层时缺失的兄弟用<b>该层的 padₖ</b> 补齐——折叠 16KiB 块层（叶子层）补 pad₀=零，
 * 折叠 piece 层补 pad[log2(每件块数)]。种子内只存"一哈希=一 piece"的那一层
 * （piece-layer 哈希带）。
 */
public final class MerkleHashes {

    /** SHA-256 摘要宽度（也是填充哈希的宽度）。 */
    public static final int HASH_WIDTH = 32;
    /** 填充哈希：32 个零字节（区别于空输入的 SHA-256）。 */
    public static final byte[] ZERO_HASH = new byte[HASH_WIDTH];

    private MerkleHashes() {
    }

    /** 一个 16KiB 块的叶子哈希。 */
    public static byte[] leafHash(byte[] block) {
        return sha256(block);
    }

    /**
     * 层归并出根（叶子层语义，pad₀ = 零哈希）：哈希数不足 2 的幂时以零哈希补齐，
     * 再逐层两两归并。单哈希层即根。用于块层折叠（单件校验）。
     */
    public static byte[] rootOfLayer(List<byte[]> layer) {
        return rootOfLayer(layer, 0);
    }

    /**
     * 层归并出根（带层高）：{@code layersAboveLeaf} = 输入层距叶子层的层数——
     * piece 层（0=叶层自身）等非叶层折叠时以 pad[层高] 起补（见类注释的填充约定），
     * 逐层归并时间隔平方。种子加载校验（层带 → pieces root）用此重载。
     */
    public static byte[] rootOfLayer(List<byte[]> layer, int layersAboveLeaf) {
        if (layer.isEmpty()) {
            throw new IllegalArgumentException("merkle layer must not be empty");
        }
        List<byte[]> current = layer;
        byte[] pad = padHash(layersAboveLeaf);
        while (current.size() > 1) {
            current = foldOnce(current, pad);
            pad = sha256(pad, pad);
        }
        return current.get(0);
    }

    /**
     * 第 {@code layersAboveLeaf} 层的填充哈希（零链）：覆盖 2^层 数个零填充叶的
     * 子树根。pad[0] 即 32 字节零哈希。
     */
    public static byte[] padHash(int layersAboveLeaf) {
        byte[] pad = ZERO_HASH;
        for (int i = 0; i < layersAboveLeaf; i++) {
            pad = sha256(pad, pad);
        }
        return pad;
    }

    private static List<byte[]> foldOnce(List<byte[]> layer, byte[] pad) {
        // 已是 2 的幂则保持，否则向上取整到 2 的幂（保证折叠后规模严格减半）
        int size = layer.size();
        int padded = Integer.bitCount(size) == 1 ? size : Integer.highestOneBit(size) << 1;
        List<byte[]> next = new ArrayList<>(padded / 2);
        for (int i = 0; i < padded; i += 2) {
            byte[] left = i < size ? layer.get(i) : pad;
            byte[] right = i + 1 < size ? layer.get(i + 1) : pad;
            byte[] parent = new byte[HASH_WIDTH * 2];
            System.arraycopy(left, 0, parent, 0, HASH_WIDTH);
            System.arraycopy(right, 0, parent, HASH_WIDTH, HASH_WIDTH);
            next.add(sha256(parent));
        }
        return next;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
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
