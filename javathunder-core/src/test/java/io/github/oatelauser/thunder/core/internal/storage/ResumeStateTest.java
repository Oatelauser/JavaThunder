package io.github.oatelauser.thunder.core.internal.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 格式见 DESIGN §5.7：magic/版本/info-hash/位图/计数器，全文 CRC32，小端序。 */
class ResumeStateTest {

    @TempDir
    Path tempDir;

    private static byte[] infoHash(char seed) {
        byte[] hash = new byte[20];
        Arrays.fill(hash, (byte) seed);
        return hash;
    }

    private static ResumeState sample() {
        Bitfield bitfield = new Bitfield(10);
        bitfield.set(0);
        bitfield.set(3);
        return new ResumeState(infoHash('J'), 10, bitfield, 111_111, 222_222, 1_700_000_000_000L);
    }

    @Test
    void roundTripsThroughDisk() throws IOException {
        Path file = tempDir.resolve("store.bin.jt-resume");
        ResumeState.save(file, sample());

        assertTrue(Files.exists(file));
        ResumeState loaded = ResumeState.load(file, infoHash('J'), 10);

        assertArrayEquals(infoHash('J'), loaded.infoHash());
        assertEquals(10, loaded.pieceCount());
        assertEquals(sample().completed(), loaded.completed());
        assertEquals(111_111, loaded.uploaded());
        assertEquals(222_222, loaded.downloaded());
        assertEquals(1_700_000_000_000L, loaded.lastActiveEpochMs());
    }

    @Test
    void rejectsWrongInfoHash() throws IOException {
        Path file = tempDir.resolve("store.bin.jt-resume");
        ResumeState.save(file, sample());
        assertThrows(ResumeException.class, () -> ResumeState.load(file, infoHash('X'), 10));
    }

    @Test
    void rejectsWrongPieceCount() throws IOException {
        Path file = tempDir.resolve("store.bin.jt-resume");
        ResumeState.save(file, sample());
        assertThrows(ResumeException.class, () -> ResumeState.load(file, infoHash('J'), 9));
    }

    @Test
    void rejectsCorruptFile() throws IOException {
        Path file = tempDir.resolve("store.bin.jt-resume");
        ResumeState.save(file, sample());
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 5] ^= 0x55; // 破坏 CRC 覆盖区内一个字节
        Files.write(file, bytes);
        assertThrows(ResumeException.class, () -> ResumeState.load(file, infoHash('J'), 10));
    }

    @Test
    void rejectsBadMagicAndVersionAndTruncation() throws IOException {
        Path file = tempDir.resolve("store.bin.jt-resume");
        ResumeState.save(file, sample());

        byte[] bytes = Files.readAllBytes(file);
        bytes[0] = 'X';
        Files.write(file, bytes);
        assertThrows(ResumeException.class, () -> ResumeState.load(file, infoHash('J'), 10));

        ResumeState.save(file, sample());
        bytes = Files.readAllBytes(file);
        bytes[9] = 9; // 版本高位，版本号变成非 1
        // 重算会绕过 CRC 校验吗？不会——测试不重算 CRC，先破坏版本再直接破坏末位 CRC
        bytes[bytes.length - 1] ^= 0xFF;
        Files.write(file, bytes);
        assertThrows(ResumeException.class, () -> ResumeState.load(file, infoHash('J'), 10));

        Files.write(file, new byte[20]); // 随机短文件
        assertThrows(ResumeException.class, () -> ResumeState.load(file, infoHash('J'), 10));
    }

    @Test
    void deleteRemovesStateFile() throws IOException {
        Path file = tempDir.resolve("store.bin.jt-resume");
        ResumeState.save(file, sample());
        ResumeState.delete(file);
        assertFalse(Files.exists(file));
        assertDoesNotThrow(() -> ResumeState.delete(file)); // 幂等
    }
}
