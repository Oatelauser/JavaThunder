package io.github.oatelauser.thunder.core.internal.storage;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多文件存储直测（B3）：拼接流布局 = a.bin(600) + nested/b.json(130) + c.txt(256)
 * + empty.marker(0)，共 986B，piece 256B → 4 件。件 2 [512,768) 跨 a.bin/b.json/c.txt
 * 两条文件边界；件 3 [768,986) 为 218B 短末件（只在 c.txt 内）。期望哈希由测试自行计算。
 */
class MultiFileStorageTest {

    @TempDir
    Path tempDir;

    private static final int PIECE_LENGTH = 256;
    private static final String NAME = "model";

    private static byte[] sha1(byte[] data, int from, int to) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(data, from, to - from);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 拼接流确定性内容（与文件清单布局一一对应）。 */
    private static byte[] stream() {
        byte[] data = new byte[600 + 130 + 256];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 29 + 11);
        }
        return data;
    }

    /** 构造与 stream() 匹配的多文件 .torrent 字节（默认布局）。 */
    private static byte[] torrentBytes(byte[] stream) {
        return torrentBytes(stream, new BList(Arrays.asList(
            fileDict(600, "a.bin"),
            fileDict(130, "nested", "b.json"),
            fileDict(256, "c.txt"),
            fileDict(0, "empty.marker"))));
    }

    /** 构造指定文件清单布局的 .torrent 字节（期望哈希按 stream() 内容计算）。 */
    private static byte[] torrentBytes(byte[] stream, BList fileDicts) {
        int pieceCount = (stream.length + PIECE_LENGTH - 1) / PIECE_LENGTH;
        byte[] pieces = new byte[pieceCount * 20];
        for (int p = 0; p < pieceCount; p++) {
            int from = p * PIECE_LENGTH;
            int to = Math.min(from + PIECE_LENGTH, stream.length);
            System.arraycopy(sha1(stream, from, to), 0, pieces, p * 20, 20);
        }
        Map<BString, BencodeValue> info = new TreeMap<>(BString.UNSIGNED_ORDER);
        info.put(BString.of("name"), BString.of(NAME));
        info.put(BString.of("piece length"), new BInteger(PIECE_LENGTH));
        info.put(BString.of("pieces"), new BString(pieces));
        info.put(BString.of("files"), fileDicts);
        Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
        top.put(BString.of("announce"), BString.of("http://t/announce"));
        top.put(BString.of("info"), new BDict(info));
        return Bencode.encode(new BDict(top));
    }

    private static BencodeValue fileDict(long length, String... path) {
        Map<BString, BencodeValue> entry = new TreeMap<>(BString.UNSIGNED_ORDER);
        entry.put(BString.of("length"), new BInteger(length));
        entry.put(BString.of("path"), new BList(Arrays.stream(path)
            .map(BString::of).map(v -> (BencodeValue) v).toList()));
        return new BDict(entry);
    }

    private static TorrentMetadata meta(byte[] stream) throws IOException {
        return TorrentParser.parse(torrentBytes(stream));
    }

    private static void writeAllPieces(MultiFileStorage storage, byte[] stream) throws IOException {
        for (int p = 0; p < storage.pieceCount(); p++) {
            int from = p * PIECE_LENGTH;
            int to = Math.min(from + PIECE_LENGTH, stream.length);
            storage.writePieceBuffers(p, new ByteBuffer[]{ByteBuffer.wrap(stream, from, to - from)});
            assertTrue(storage.verifyPiece(p), "piece " + p + " should verify after write");
        }
    }

    @Test
    void writePieceBuffersAcrossFileBoundariesVerifies() throws IOException {
        byte[] stream = stream();
        try (MultiFileStorage storage = new MultiFileStorage(meta(stream), tempDir)) {
            assertEquals(4, storage.pieceCount());
            // 件 2 [512,768)：跨 a.bin→b.json→c.txt 两条边界，单个缓冲 scatter 写
            storage.writePieceBuffers(2,
                new ByteBuffer[]{ByteBuffer.wrap(stream, 512, 256)});
            assertTrue(storage.verifyPiece(2));

            storage.writePieceBuffers(0, new ByteBuffer[]{ByteBuffer.wrap(stream, 0, 256)});
            storage.writePieceBuffers(1, new ByteBuffer[]{ByteBuffer.wrap(stream, 256, 256)});
            assertTrue(storage.verifyPiece(0));
            assertTrue(storage.verifyPiece(1));

            // 跨边界读回：b.json 全段 + c.txt 头 38B（拼接流 [600,768)）
            assertArrayEquals(Arrays.copyOfRange(stream, 600, 768),
                storage.readBlock(2, 88, 168));
        }
    }

    @Test
    void shortLastPieceTruncatesToRemainingLengthAndReadsBack() throws IOException {
        byte[] stream = stream();
        try (MultiFileStorage storage = new MultiFileStorage(meta(stream), tempDir)) {
            assertEquals(218, storage.pieceLengthOf(3)); // 986 - 3*256

            storage.writePieceBuffers(3, new ByteBuffer[]{ByteBuffer.wrap(stream, 768, 218)});
            assertTrue(storage.verifyPiece(3));
            assertArrayEquals(Arrays.copyOfRange(stream, 768, 986),
                storage.readBlock(3, 0, 218)); // 读回与写入一致
        }
    }

    @Test
    void verifyPieceRejectsTamperedBytesAndClearAllowsRewrite() throws IOException {
        byte[] stream = stream();
        try (MultiFileStorage storage = new MultiFileStorage(meta(stream), tempDir)) {
            byte[] tampered = Arrays.copyOfRange(stream, 0, 256);
            tampered[100] ^= 0x5A;
            storage.writePieceBuffers(0, new ByteBuffer[]{ByteBuffer.wrap(tampered)});
            assertFalse(storage.verifyPiece(0));

            storage.clearPiece(0);
            storage.writePieceBuffers(0, new ByteBuffer[]{ByteBuffer.wrap(stream, 0, 256)});
            assertTrue(storage.verifyPiece(0));
        }
    }

    @Test
    void finishPlacesNumberedPartsIntoTargetTreeAndRemovesPartDir() throws IOException {
        byte[] stream = stream();
        try (MultiFileStorage storage = new MultiFileStorage(meta(stream), tempDir)) {
            Path partRoot = tempDir.resolve(NAME + ".part");
            assertEquals(partRoot, storage.partFile());
            assertEquals(tempDir.resolve(NAME), storage.finalFile());

            writeAllPieces(storage, stream);
            assertTrue(Files.isDirectory(partRoot)); // 下载期：编号暂存形态
            assertFalse(Files.exists(tempDir.resolve(NAME)));

            storage.finish();
            Path root = tempDir.resolve(NAME);
            assertTrue(Files.exists(root.resolve("a.bin")));
            assertTrue(Files.exists(root.resolve("nested").resolve("b.json")));
            assertTrue(Files.exists(root.resolve("c.txt")));
            assertTrue(Files.exists(root.resolve("empty.marker"))); // 空文件占位
            assertFalse(Files.exists(partRoot)); // finish 清掉暂存目录
            assertArrayEquals(Arrays.copyOfRange(stream, 0, 600),
                Files.readAllBytes(root.resolve("a.bin")));
            assertArrayEquals(Arrays.copyOfRange(stream, 600, 730),
                Files.readAllBytes(root.resolve("nested").resolve("b.json")));
            assertArrayEquals(Arrays.copyOfRange(stream, 730, 986),
                Files.readAllBytes(root.resolve("c.txt")));
        }
    }

    /** 回归：0 字节文件位于流中段时，跨界写/清零必须跳过其零长段（无 channel，修复前 NPE）。 */
    @Test
    void writeAcrossMidStreamEmptyFileSkipsItsZeroLengthSegment() throws IOException {
        // 布局：a.bin(600) + empty.mid(0，流中段) + b.bin(256) = 856B → 4 件（末件 88B）
        byte[] stream = new byte[856];
        for (int i = 0; i < stream.length; i++) {
            stream[i] = (byte) (i * 31 + 7);
        }
        BList fileDicts = new BList(Arrays.asList(
            fileDict(600, "a.bin"),
            fileDict(0, "empty.mid"),
            fileDict(256, "b.bin")));
        try (MultiFileStorage storage =
                new MultiFileStorage(TorrentParser.parse(torrentBytes(stream, fileDicts)), tempDir)) {
            assertEquals(4, storage.pieceCount());
            // 件 2 [512,768)：字节区间横跨 empty.mid 的边界位置 600——修复前此处 NPE
            storage.writePieceBuffers(2, new ByteBuffer[]{ByteBuffer.wrap(stream, 512, 256)});
            assertTrue(storage.verifyPiece(2));
            assertArrayEquals(Arrays.copyOfRange(stream, 512, 768),
                storage.readBlock(2, 0, 256));
            // 清零路径同样跨界：清掉重写仍通过
            storage.clearPiece(2);
            storage.writePieceBuffers(2, new ByteBuffer[]{ByteBuffer.wrap(stream, 512, 256)});
            assertTrue(storage.verifyPiece(2));
        }
    }

    @Test
    void adoptExistingDataModeVerifiesTreeInPlaceWithoutPartDir() throws IOException {
        byte[] stream = stream();
        Path root = tempDir.resolve(NAME); // 预置完整目录树（seed-only 导入形态）
        Files.createDirectories(root.resolve("nested"));
        Files.write(root.resolve("a.bin"), Arrays.copyOfRange(stream, 0, 600));
        Files.write(root.resolve("nested").resolve("b.json"), Arrays.copyOfRange(stream, 600, 730));
        Files.write(root.resolve("c.txt"), Arrays.copyOfRange(stream, 730, 986));
        Files.write(root.resolve("empty.marker"), new byte[0]);

        try (MultiFileStorage storage = new MultiFileStorage(meta(stream), tempDir, true)) {
            assertFalse(Files.exists(tempDir.resolve(NAME + ".part"))); // 无暂存目录
            for (int p = 0; p < storage.pieceCount(); p++) {
                assertTrue(storage.verifyPiece(p), "existing piece " + p + " should verify");
            }
            storage.finish(); // 导入模式：不 move 不清理
            assertTrue(Files.exists(root.resolve("a.bin")));
            assertFalse(Files.exists(tempDir.resolve(NAME + ".part")));
            assertEquals(600, Files.size(root.resolve("a.bin")));
        }
    }
}
