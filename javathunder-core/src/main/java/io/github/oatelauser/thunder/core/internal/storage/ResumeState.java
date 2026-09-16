package io.github.oatelauser.thunder.core.internal.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * 断点续传状态（DESIGN §5.7），小端序二进制：
 *
 * <pre>
 * 0    8   magic "JTRESUME"
 * 8    2   版本 u16（=1）
 * 10   20  info-hash（与种子绑定）
 * 30   4   pieceCount u32
 * 34   N   Bitfield（N=⌈pieceCount/8⌉）
 * …    8   uploaded u64
 * …    8   downloaded u64
 * …    8   lastActiveEpochMs u64
 * …    4   CRC32（覆盖此前全部字节）
 * </pre>
 *
 * <p>写入原子性：先写 {@code <file>.tmp} 再 move，读端任何校验失败抛 {@link ResumeException}。
 */
public record ResumeState(byte[] infoHash, int pieceCount, Bitfield completed,
                          long uploaded, long downloaded, long lastActiveEpochMs) {

    private static final byte[] MAGIC = "JTRESUME".getBytes(StandardCharsets.US_ASCII);
    private static final short VERSION = 1;

    public ResumeState {
        if (infoHash.length != 20) {
            throw new IllegalArgumentException("info-hash must be 20 bytes");
        }
        if (completed.size() != pieceCount) {
            throw new IllegalArgumentException("bitfield size must equal pieceCount");
        }
        infoHash = infoHash.clone();
    }

    public static void save(Path file, ResumeState state) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(encode(state));
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public static ResumeState load(Path file, byte[] expectedInfoHash, int expectedPieceCount) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ResumeException("cannot read resume file " + file + ": " + e.getMessage());
        }
        int bitfieldLength = (expectedPieceCount + 7) / 8;
        int minLength = MAGIC.length + 2 + 20 + 4 + bitfieldLength + 8 + 8 + 8 + 4;
        if (bytes.length != minLength) {
            throw new ResumeException("resume file size " + bytes.length + " != expected " + minLength);
        }
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        buf.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new ResumeException("resume file magic mismatch");
        }
        if (buf.getShort() != VERSION) {
            throw new ResumeException("resume file version mismatch");
        }
        byte[] infoHash = new byte[20];
        buf.get(infoHash);
        if (!Arrays.equals(infoHash, expectedInfoHash)) {
            throw new ResumeException("resume file belongs to a different torrent");
        }
        int pieceCount = buf.getInt();
        if (pieceCount != expectedPieceCount) {
            throw new ResumeException("resume pieceCount " + pieceCount + " != " + expectedPieceCount);
        }
        byte[] bits = new byte[bitfieldLength];
        buf.get(bits);
        Bitfield completed = Bitfield.fromBytes(bits, expectedPieceCount);
        long uploaded = buf.getLong();
        long downloaded = buf.getLong();
        long lastActiveEpochMs = buf.getLong();
        int storedCrc = buf.getInt();
        if ((int) crc.getValue() != storedCrc) {
            throw new ResumeException("resume file CRC mismatch");
        }
        return new ResumeState(infoHash, pieceCount, completed, uploaded, downloaded, lastActiveEpochMs);
    }

    /**
     * 删除状态文件（连同下载文件一起删除任务时调用）。幂等。
     */
    public static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new ResumeException("cannot delete resume file " + file + ": " + e.getMessage());
        }
    }

    private static ByteBuffer encode(ResumeState state) {
        byte[] bits = state.completed().toBytes();
        ByteBuffer buf = ByteBuffer
                .allocate(MAGIC.length + 2 + 20 + 4 + bits.length + 8 + 8 + 8 + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.putShort(VERSION);
        buf.put(state.infoHash());
        buf.putInt(state.pieceCount());
        buf.put(bits);
        buf.putLong(state.uploaded());
        buf.putLong(state.downloaded());
        buf.putLong(state.lastActiveEpochMs());
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, buf.position());
        buf.putInt((int) crc.getValue());
        buf.flip();
        return buf;
    }
}
