package io.github.oatelauser.thunder.core.internal.metainfo;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEP 52 哈希交换数学单测：构造 32KiB piece（pieceLayer=1）、5 件（非 2 的幂——
 * 覆盖填充链约定 pad₁ = SHA-256(零‖零)，区别于逐层补零）的文件树，验证
 * buildChunk 生成的层哈希/证明能被 verifyChunk 按 pieces root 验证，以及
 * 各种形状不合法的请求被拒绝。
 */
class MerkleProofsTest {

    private static final int PIECE_LENGTH = 32 * 1024; // 2 块/件 → pieceLayer = 1
    private static final int FILE_PIECES = 5;
    private static final int TREE_HEIGHT = MerkleProofs.log2(MerkleProofs.nextPow2(FILE_PIECES)) + 1;

    private final Random random = new Random(1234);
    private final byte[] content = content();
    private final List<byte[]> pieceHashes = pieceHashes();
    private final byte[] strip = stripOf(pieceHashes);
    private final byte[] root = MerkleHashes.rootOfLayer(pieceHashes, 1);

    @Test
    void fullWidthChunkNeedsNoProof() {
        // 整层（含填充到 8 件）：区间自身折叠即根
        MerkleProofs.Chunk chunk = MerkleProofs.buildChunk(strip, FILE_PIECES, 1,
                1, 0, 8, MerkleProofs.log2(8) - 1);
        assertNotNull(chunk);
        assertEquals(8, chunk.hashes().size());
        assertEquals(0, chunk.proof().size(), "整层覆盖 → 无 uncle");
        for (int i = 0; i < FILE_PIECES; i++) {
            assertArrayEquals(pieceHashes.get(i), chunk.hashes().get(i));
        }
        for (int i = FILE_PIECES; i < 8; i++) {
            assertArrayEquals(MerkleProofs.padNode(1), chunk.hashes().get(i),
                    "越尾部分必须是零链填充 pad₁");
        }
        assertTrue(MerkleProofs.verifyChunk(root, 1, 0, chunk.hashes(), chunk.proof(), TREE_HEIGHT));
    }

    @Test
    void halfWidthChunkVerifiesViaUncleChain() {
        MerkleProofs.Chunk left = MerkleProofs.buildChunk(strip, FILE_PIECES, 1,
                1, 0, 4, MerkleProofs.log2(8) - 1);
        MerkleProofs.Chunk right = MerkleProofs.buildChunk(strip, FILE_PIECES, 1,
                1, 4, 4, MerkleProofs.log2(8) - 1);
        assertNotNull(left);
        assertNotNull(right);
        assertEquals(1, left.proof().size(), "proofLayers - log2(4) + 1 = 1");
        assertTrue(MerkleProofs.verifyChunk(root, 1, 0, left.hashes(), left.proof(), TREE_HEIGHT));
        assertTrue(MerkleProofs.verifyChunk(root, 1, 4, right.hashes(), right.proof(), TREE_HEIGHT));
        // 右半区间含 1 个真实件 + 3 个填充件
        assertArrayEquals(pieceHashes.get(4), right.hashes().get(0));
        assertArrayEquals(MerkleProofs.padNode(1), right.hashes().get(1));
    }

    @Test
    void tamperedHashFailsVerification() {
        MerkleProofs.Chunk chunk = MerkleProofs.buildChunk(strip, FILE_PIECES, 1,
                1, 0, 4, MerkleProofs.log2(8) - 1);
        assertNotNull(chunk);
        List<byte[]> tampered = new ArrayList<>(chunk.hashes());
        tampered.set(1, MerkleProofs.padNode(1)); // 顶替一个真实哈希
        assertFalse(MerkleProofs.verifyChunk(root, 1, 0, tampered, chunk.proof(), TREE_HEIGHT));
        // 证明链被换同样失败
        List<byte[]> badProof = List.of(MerkleProofs.padNode(1));
        assertFalse(MerkleProofs.verifyChunk(root, 1, 0, chunk.hashes(), badProof, TREE_HEIGHT));
    }

    @Test
    void malformedRequestsAreRejected() {
        assertNull(MerkleProofs.buildChunk(strip, FILE_PIECES, 1, 1, 0, 3, 2), "count 非 2 的幂");
        assertNull(MerkleProofs.buildChunk(strip, FILE_PIECES, 1, 1, 1, 4, 2), "index 未对齐");
        assertNull(MerkleProofs.buildChunk(strip, FILE_PIECES, 1, 1, 0, 4, 3), "proofLayers 越界");
        assertNull(MerkleProofs.buildChunk(strip, FILE_PIECES, 1, 1, 8, 4, 2), "区间越界");
        assertNull(MerkleProofs.buildChunk(strip, FILE_PIECES, 1, 0, 0, 4, 2), "base 非 piece 层");
        assertNull(MerkleProofs.buildChunk(strip, 1, 1, 1, 0, 1, 0), "单件文件无层带");
    }

    @Test
    void chunkedAssemblyReproducesStrip() {
        // 按 4 件一块拉齐整带（libtorrent 请求形状的分块版）：全部验证通过后拼回应等于原层带
        byte[] assembled = new byte[FILE_PIECES * MerkleHashes.HASH_WIDTH];
        for (int index = 0; index < FILE_PIECES; index += 4) {
            int count = Math.min(4, MerkleProofs.nextPow2(FILE_PIECES - index));
            MerkleProofs.Chunk chunk = MerkleProofs.buildChunk(strip, FILE_PIECES, 1,
                    1, index, count, MerkleProofs.log2(8) - 1);
            assertNotNull(chunk);
            assertTrue(MerkleProofs.verifyChunk(root, 1, index,
                    chunk.hashes(), chunk.proof(), TREE_HEIGHT), "index=" + index);
            int real = Math.min(count, FILE_PIECES - index);
            for (int i = 0; i < real; i++) {
                System.arraycopy(chunk.hashes().get(i), 0, assembled,
                        (index + i) * MerkleHashes.HASH_WIDTH, MerkleHashes.HASH_WIDTH);
            }
        }
        assertArrayEquals(strip, assembled);
    }

    @Test
    void padChainDistinguishesLayerLevels() {
        // pad₀ = 32 零字节；pad₁ = SHA-256(pad₀‖pad₀)——与 libtorrent 零链表一致
        assertArrayEquals(MerkleHashes.ZERO_HASH, MerkleProofs.padNode(0));
        assertArrayEquals(sha256(MerkleHashes.ZERO_HASH, MerkleHashes.ZERO_HASH),
                MerkleProofs.padNode(1));
        assertFalse(Arrays.equals(MerkleProofs.padNode(0), MerkleProofs.padNode(1)));
    }

    private byte[] content() {
        byte[] data = new byte[FILE_PIECES * PIECE_LENGTH];
        random.nextBytes(data);
        return data;
    }

    /** 逐件哈希：2 个 16KiB 块叶子折叠。 */
    private List<byte[]> pieceHashes() {
        List<byte[]> pieces = new ArrayList<>(FILE_PIECES);
        for (int p = 0; p < FILE_PIECES; p++) {
            byte[] left = MerkleHashes.leafHash(Arrays.copyOfRange(
                    content, p * PIECE_LENGTH, p * PIECE_LENGTH + 16384));
            byte[] right = MerkleHashes.leafHash(Arrays.copyOfRange(
                    content, p * PIECE_LENGTH + 16384, (p + 1) * PIECE_LENGTH));
            pieces.add(sha256(left, right));
        }
        return pieces;
    }

    private static byte[] stripOf(List<byte[]> hashes) {
        byte[] strip = new byte[hashes.size() * MerkleHashes.HASH_WIDTH];
        for (int i = 0; i < hashes.size(); i++) {
            System.arraycopy(hashes.get(i), 0, strip, i * MerkleHashes.HASH_WIDTH,
                    MerkleHashes.HASH_WIDTH);
        }
        return strip;
    }

    private static byte[] sha256(byte[] left, byte[] right) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(left);
            digest.update(right);
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
