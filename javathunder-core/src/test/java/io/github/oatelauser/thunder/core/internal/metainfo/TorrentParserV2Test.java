package io.github.oatelauser.thunder.core.internal.metainfo;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEP 52（v2/hybrid）解析直测。向量布局：两实文件恰好件对齐（32768 + 16384 = 3 件），
 * 避开 .pad 排序位问题（.pad 目录在 UNSIGNED_ORDER 下恒排字母文件前——填充文件的
 * 偏移语义属 S3 存储管线议题，S2 聚焦解析与校验正确性）。
 * 折叠数学由独立递归实现交叉守卫；跨客户端一致性由 S4 的 qBittorrent 对拍守卫。
 */
class TorrentParserV2Test {

    private static final int PIECE_LENGTH = 16 * 1024; // v2 最小 piece：层带即叶子层

    /** 递归折叠（与被测的逐层折叠写法不同，交叉验证索引/填充数学）。 */
    private static byte[] recursiveRoot(List<byte[]> hashes) {
        if (hashes.size() == 1) {
            return hashes.get(0);
        }
        int half = Integer.highestOneBit(hashes.size());
        if (half == hashes.size()) {
            half /= 2;
        }
        List<byte[]> left = new ArrayList<>(hashes.subList(0, half));
        List<byte[]> right = new ArrayList<>(hashes.subList(half, hashes.size()));
        while (right.size() < half) {
            right.add(new byte[32]);
        }
        while (left.size() < half) {
            left.add(new byte[32]);
        }
        byte[] parent = new byte[64];
        System.arraycopy(recursiveRoot(left), 0, parent, 0, 32);
        System.arraycopy(recursiveRoot(right), 0, parent, 32, 32);
        return sha256(parent);
    }

    @Test
    void foldImplementationsAgree() {
        for (int n : new int[]{1, 2, 3, 5, 8, 13}) {
            List<byte[]> layer = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                byte[] h = new byte[32];
                Arrays.fill(h, (byte) ('a' + i));
                layer.add(h);
            }
            assertArrayEquals(recursiveRoot(layer), MerkleHashes.rootOfLayer(layer), "n=" + n);
        }
    }

    /**
     * 造 v2 种子。正常布局：file.bin(32768B，2 件) + tail.bin(16384B，1 件) = 49152B = 3 件。
     * misalign=true 时改 file.bin(20000B) + tail.bin(1000B)——tail 起点非件对齐，触发拒绝。
     */
    private static byte[] v2Torrent(boolean includeV1Pieces, boolean tamperLayer,
            boolean misalign) {
        int file1Len = misalign ? 20000 : 32768;
        int file2Len = misalign ? 1000 : 16384;

        byte[] root1 = MerkleHashes.rootOfLayer(blocksOf(deterministic(file1Len)));
        byte[] root2 = MerkleHashes.rootOfLayer(blocksOf(deterministic(file2Len)));

        byte[] strip1 = concat(blocksOf(deterministic(file1Len)));
        if (tamperLayer) {
            strip1[5] ^= 0x5A;
        }

        Map<BString, BencodeValue> tree = new TreeMap<>(BString.UNSIGNED_ORDER);
        tree.put(BString.of("file.bin"), fileEntry(file1Len, root1));
        tree.put(BString.of("tail.bin"), fileEntry(file2Len, root2));

        Map<BString, BencodeValue> info = new TreeMap<>(BString.UNSIGNED_ORDER);
        info.put(BString.of("name"), BString.of("v2root"));
        info.put(BString.of("piece length"), new BInteger(PIECE_LENGTH));
        info.put(BString.of("meta version"), new BInteger(2));
        info.put(BString.of("file tree"), new BDict(tree));

        long total = file1Len + file2Len;
        if (includeV1Pieces) {
            byte[] hashes = new byte[(int) ((total + PIECE_LENGTH - 1) / PIECE_LENGTH) * 20];
            for (int i = 0; i < hashes.length; i++) {
                hashes[i] = (byte) i;
            }
            info.put(BString.of("pieces"), new BString(hashes));
        }

        Map<BString, BencodeValue> layers = new TreeMap<>(BString.UNSIGNED_ORDER);
        layers.put(new BString(root1), new BString(strip1));
        Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
        top.put(BString.of("announce"), BString.of("http://tracker.example/announce"));
        top.put(BString.of("piece layers"), new BDict(layers));
        top.put(BString.of("info"), new BDict(info));
        return Bencode.encode(new BDict(top));
    }

    /** file tree 文件条目：{ "" : { length, pieces root } }。 */
    private static BDict fileEntry(long length, byte[] root) {
        Map<BString, BencodeValue> attrs = new TreeMap<>(BString.UNSIGNED_ORDER);
        attrs.put(BString.of("length"), new BInteger(length));
        attrs.put(BString.of("pieces root"), new BString(root));
        Map<BString, BencodeValue> entry = new TreeMap<>(BString.UNSIGNED_ORDER);
        entry.put(new BString(new byte[0]), new BDict(attrs));
        return new BDict(entry);
    }

    @Test
    void parsesV2LayoutAndComputesHashes() {
        TorrentMetadata meta = TorrentParser.parse(v2Torrent(false, false, false));
        assertEquals(TorrentVersion.V2, meta.version());
        assertEquals(2, meta.files().size());
        assertEquals(0, meta.files().get(0).offset());
        assertEquals(32768, meta.files().get(1).offset());
        assertNotNull(meta.files().get(0).piecesRoot());
        assertEquals(32768 + 16384, meta.length());
        assertEquals(3, meta.pieceCount(), "总长 49152B / 16KiB piece = 3 件");
        assertEquals(32, meta.infoHashV2().length);
        assertEquals(20, meta.infoHash().length);
        assertArrayEquals(Arrays.copyOf(meta.infoHashV2(), 20), meta.infoHash(),
                "v2-only 主哈希 = SHA-256 截断前 20 字节");
        assertThrows(UnsupportedOperationException.class, () -> meta.pieceHash(0));
    }

    @Test
    void parsesHybridWithBothHashesOverIdenticalRawBytes() {
        byte[] torrent = v2Torrent(true, false, false);
        TorrentMetadata meta = TorrentParser.parse(torrent);
        assertEquals(TorrentVersion.HYBRID, meta.version());
        // 双哈希来自同一份原始 info 字节：用提取出的 info 区间独立复算核对
        byte[] infoRaw = TorrentParser.extractInfoDict(torrent);
        assertArrayEquals(sha1(infoRaw), meta.infoHash());
        assertArrayEquals(sha256(infoRaw), meta.infoHashV2());
        assertEquals(3, meta.pieceCount());
        assertEquals(20, meta.pieceHash(0).length, "hybrid 的 v1 面照常可用");
    }

    @Test
    void tamperedLayerIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TorrentParser.parse(v2Torrent(false, true, false)));
        assertTrue(e.getMessage().contains("fold to pieces root"), e.getMessage());
    }

    @Test
    void misalignedSecondFileIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TorrentParser.parse(v2Torrent(false, false, true)));
        assertTrue(e.getMessage().contains("aligned"), e.getMessage());
    }

    @Test
    void nonPowerOfTwoPieceLengthIsRejected() {
        // 二进制安全地把 piece length 从 16384 换到 24576（5 位数字，结构不变）
        byte[] torrent = v2Torrent(false, false, false);
        byte[] mutated = new String(torrent, StandardCharsets.ISO_8859_1)
                .replace("12:piece lengthi16384e", "12:piece lengthi24576e")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(IllegalArgumentException.class, () -> TorrentParser.parse(mutated));
    }

    private static List<byte[]> blocksOf(byte[] data) {
        List<byte[]> blocks = new ArrayList<>();
        for (int off = 0; off < data.length; off += 16 * 1024) {
            int len = Math.min(16 * 1024, data.length - off);
            blocks.add(MerkleHashes.leafHash(Arrays.copyOfRange(data, off, off + len)));
        }
        if (blocks.isEmpty()) {
            blocks.add(MerkleHashes.leafHash(new byte[0]));
        }
        return blocks;
    }

    private static byte[] deterministic(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i * 7 + 3);
        }
        return data;
    }

    private static byte[] concat(List<byte[]> hashes) {
        byte[] out = new byte[hashes.size() * 32];
        for (int i = 0; i < hashes.size(); i++) {
            System.arraycopy(hashes.get(i), 0, out, i * 32, 32);
        }
        return out;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
