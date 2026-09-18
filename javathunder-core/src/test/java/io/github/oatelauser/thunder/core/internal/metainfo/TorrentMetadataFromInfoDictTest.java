package io.github.oatelauser.thunder.core.internal.metainfo;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 磁力路径（BEP 9 裸 info 字典字节）构造元数据的哈希口径单测：主 infoHash 与
 * v2 副哈希都必须覆盖<b>真实 info 字节</b>——回归守卫 infoHashV2 曾算成
 * sha256("") 的缺陷（Scanned.infoRawBytes 透传修复）。
 */
class TorrentMetadataFromInfoDictTest {

    private static final String TRACKER = "http://tracker.example/announce";
    private static final int PIECE_LENGTH = 16 * 1024;

    /**
     * 手造 v2 info 字典原始字节：name + piece length + meta version + file tree
     * （单文件 1024B，单 piece → 层带可选，磁力路径 lazyLayers 不校验层带）。
     * hybrid=true 时补 pieces（1 件 = 20 字节 v1 哈希带）。
     */
    private static byte[] v2InfoBytes(boolean hybrid) {
        Map<BString, BencodeValue> attrs = new TreeMap<>(BString.UNSIGNED_ORDER);
        attrs.put(BString.of("length"), new BInteger(1024));
        attrs.put(BString.of("pieces root"), new BString(root32()));
        Map<BString, BencodeValue> fileEntry = new TreeMap<>(BString.UNSIGNED_ORDER);
        fileEntry.put(new BString(new byte[0]), new BDict(attrs));
        Map<BString, BencodeValue> tree = new TreeMap<>(BString.UNSIGNED_ORDER);
        tree.put(BString.of("a.bin"), new BDict(fileEntry));

        Map<BString, BencodeValue> info = new TreeMap<>(BString.UNSIGNED_ORDER);
        info.put(BString.of("name"), BString.of("v2magnet"));
        info.put(BString.of("piece length"), new BInteger(PIECE_LENGTH));
        info.put(BString.of("meta version"), new BInteger(2));
        info.put(BString.of("file tree"), new BDict(tree));
        if (hybrid) {
            info.put(BString.of("pieces"), new BString(new byte[20]));
        }
        return Bencode.encode(new BDict(info));
    }

    private static byte[] root32() {
        byte[] root = new byte[32];
        Arrays.fill(root, (byte) 0xAB);
        return root;
    }

    @Test
    void v2SecondaryHashCoversRealInfoBytes() {
        byte[] infoBytes = v2InfoBytes(false);
        TorrentMetadata meta = TorrentMetadata.fromInfoDict(infoBytes, List.of(TRACKER));

        assertEquals(TorrentVersion.V2, meta.version());
        byte[] sha256 = sha256(infoBytes);
        assertArrayEquals(sha256, meta.infoHashV2(), "v2 副哈希 = 真实 info 字节的 SHA-256");
        assertArrayEquals(Arrays.copyOf(sha256, 20), meta.infoHash(), "主哈希仍是截断 20 字节口径");
    }

    @Test
    void hybridKeepsSha1PrimaryAndSha256SecondaryOverRealBytes() {
        byte[] infoBytes = v2InfoBytes(true);
        TorrentMetadata meta = TorrentMetadata.fromInfoDict(infoBytes, List.of(TRACKER));

        assertEquals(TorrentVersion.HYBRID, meta.version());
        assertArrayEquals(sha1(infoBytes), meta.infoHash());
        assertArrayEquals(sha256(infoBytes), meta.infoHashV2());
    }

    private static byte[] sha1(byte[] data) {
        return digest("SHA-1", data);
    }

    private static byte[] sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
