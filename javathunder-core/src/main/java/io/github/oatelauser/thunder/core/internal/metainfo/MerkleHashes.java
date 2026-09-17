package io.github.oatelauser.thunder.core.internal.metainfo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * BEP 52 Merkle 哈希树数学（纯函数）：叶子恒为 16KiB 块的 SHA-256；非 2 的幂层以
 * 32 字节零哈希填充（规范原文"set to zero"，非空串哈希）；父 = SHA-256(左‖右)。
 * 种子内只存"一哈希=一 piece"的那一层（piece-layer 哈希带）。
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
     * 层归并出根：哈希数不足 2 的幂时以零哈希补齐到 2 的幂，再逐层两两归并。
     * 单哈希层即根。用于种子加载校验（层带 → pieces root）。
     */
    public static byte[] rootOfLayer(List<byte[]> layer) {
        if (layer.isEmpty()) {
            throw new IllegalArgumentException("merkle layer must not be empty");
        }
        List<byte[]> current = layer;
        while (current.size() > 1) {
            current = foldOnce(current);
        }
        return current.get(0);
    }

    private static List<byte[]> foldOnce(List<byte[]> layer) {
        // 已是 2 的幂则保持，否则向上取整到 2 的幂（保证折叠后规模严格减半）
        int size = layer.size();
        int padded = Integer.bitCount(size) == 1 ? size : Integer.highestOneBit(size) << 1;
        List<byte[]> next = new ArrayList<>(padded / 2);
        for (int i = 0; i < padded; i += 2) {
            byte[] left = i < size ? layer.get(i) : ZERO_HASH;
            byte[] right = i + 1 < size ? layer.get(i + 1) : ZERO_HASH;
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
}
