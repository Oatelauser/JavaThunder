package io.github.oatelauser.thunder.core.internal.storage;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 640 字节 / 256B piece → 3 个 piece，末块 128B。期望哈希由测试自行计算。 */
class StorageManagerTest {

    @TempDir
    Path tempDir;

    private static final int PIECE_LENGTH = 256;
    private static final long TOTAL_LENGTH = 640;

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] content() {
        byte[] data = new byte[(int) TOTAL_LENGTH];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    /** 构造与 content() 匹配的 .torrent 字节。 */
    private static byte[] torrentBytes(byte[] content) {
        byte[] pieces = new byte[60];
        for (int p = 0; p < 3; p++) {
            int from = p * PIECE_LENGTH;
            int to = Math.min(from + PIECE_LENGTH, content.length);
            byte[] hash = sha1(Arrays.copyOfRange(content, from, to));
            System.arraycopy(hash, 0, pieces, p * 20, 20);
        }
        Map<BString, BencodeValue> info = new TreeMap<>(BString.UNSIGNED_ORDER);
        info.put(BString.of("name"), BString.of("store.bin"));
        info.put(BString.of("piece length"), new BInteger(PIECE_LENGTH));
        info.put(BString.of("length"), new BInteger(TOTAL_LENGTH));
        info.put(BString.of("pieces"), new BString(pieces));
        Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
        top.put(BString.of("announce"), BString.of("http://t/announce"));
        top.put(BString.of("info"), new BDict(info));
        return Bencode.encode(new BDict(top));
    }

    private StorageManager open(byte[] content) throws IOException {
        TorrentMetadata meta = TorrentParser.parse(torrentBytes(content));
        return new StorageManager(meta, tempDir);
    }

    @Test
    void preallocatesPartFileAtFullLength() throws IOException {
        byte[] content = content();
        try (StorageManager storage = open(content)) {
            Path part = tempDir.resolve("store.bin.part");
            assertEquals(TOTAL_LENGTH, Files.size(part));
        }
    }

    @Test
    void writeBlockLandsAtPieceOffsetPlusBegin() throws IOException {
        byte[] content = content();
        try (StorageManager storage = open(content)) {
            byte[] block = Arrays.copyOfRange(content, PIECE_LENGTH + 128, PIECE_LENGTH + 192);
            storage.writeBlock(1, 128, block);

            Path part = tempDir.resolve("store.bin.part");
            byte[] written = new byte[64];
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.READ)) {
                channel.position(PIECE_LENGTH + 128);
                channel.read(ByteBuffer.wrap(written));
            }
            assertArrayEquals(block, written);
        }
    }

    @Test
    void verifyPieceAcceptsCorrectDataIncludingShortLastPiece() throws IOException {
        byte[] content = content();
        try (StorageManager storage = open(content)) {
            storage.writeBlock(0, 0, Arrays.copyOfRange(content, 0, 256));
            assertTrue(storage.verifyPiece(0));

            storage.writeBlock(2, 0, Arrays.copyOfRange(content, 512, 640)); // 末块 128B
            assertTrue(storage.verifyPiece(2));
        }
    }

    @Test
    void verifyPieceRejectsWrongDataAndClearAllowsRedownload() throws IOException {
        byte[] content = content();
        try (StorageManager storage = open(content)) {
            byte[] bad = new byte[256];
            Arrays.fill(bad, (byte) 0xAB);
            storage.writeBlock(0, 0, bad);
            assertFalse(storage.verifyPiece(0));

            storage.clearPiece(0);
            storage.writeBlock(0, 0, Arrays.copyOfRange(content, 0, 256));
            assertTrue(storage.verifyPiece(0));
        }
    }

    @Test
    void outOfRangeWritesAreRejected() throws IOException {
        byte[] content = content();
        try (StorageManager storage = open(content)) {
            assertThrows(IllegalArgumentException.class,
                () -> storage.writeBlock(3, 0, new byte[16]));  // piece 越界
            assertThrows(IllegalArgumentException.class,
                () -> storage.writeBlock(0, 240, new byte[32])); // begin+len 越界
            assertThrows(IllegalArgumentException.class,
                () -> storage.writeBlock(2, 0, new byte[256]));  // 末块只有 128B
        }
    }

    @Test
    void readBlockReturnsWhatWasWritten() throws IOException {
        byte[] content = content();
        try (StorageManager storage = open(content)) {
            storage.writeBlock(1, 128, Arrays.copyOfRange(content, 256 + 128, 256 + 192));
            byte[] block = storage.readBlock(1, 128, 64);
            assertArrayEquals(Arrays.copyOfRange(content, 256 + 128, 256 + 192), block);
        }
    }

    @Test
    void finishRenamesPartToFinalName() throws IOException {
        byte[] content = content();
        try (StorageManager storage = open(content)) {
            for (int p = 0; p < 3; p++) {
                int from = p * PIECE_LENGTH;
                int to = Math.min(from + PIECE_LENGTH, content.length);
                storage.writeBlock(p, 0, Arrays.copyOfRange(content, from, to));
                storage.verifyPiece(p);
            }
            storage.finish();

            assertTrue(Files.exists(tempDir.resolve("store.bin")));
            assertFalse(Files.exists(tempDir.resolve("store.bin.part")));
            assertEquals(TOTAL_LENGTH, Files.size(tempDir.resolve("store.bin")));
        }
    }
}
