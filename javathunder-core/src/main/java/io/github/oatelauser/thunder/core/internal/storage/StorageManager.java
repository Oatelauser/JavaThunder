package io.github.oatelauser.thunder.core.internal.storage;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * 单文件存储（DESIGN §5.5）：目标文件以 {@code <name>.part} 预分配全尺寸，
 * Block 直写最终偏移（内存占用与 Piece 大小无关），Piece 齐后读回 SHA-1 校验。
 * 位置式读写线程安全，多 Peer 虚拟线程可并发写入不同 Piece。
 */
public final class StorageManager implements AutoCloseable {

    private final TorrentMetadata meta;
    private final Path partFile;
    private final Path finalFile;
    private final FileChannel channel;

    public StorageManager(TorrentMetadata meta, Path targetDir) throws IOException {
        this.meta = meta;
        Files.createDirectories(targetDir);
        this.finalFile = targetDir.resolve(meta.name());
        this.partFile = targetDir.resolve(meta.name() + ".part");
        this.channel = FileChannel.open(partFile,
            StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        // 预分配：截长防上次异常残留，再在末位写一字节撑出全尺寸（稀疏）
        channel.truncate(meta.length());
        if (meta.length() > 0) {
            channel.write(ByteBuffer.wrap(new byte[1]), meta.length() - 1);
        }
    }

    /** Block 落盘：写入位置 = pieceIndex × pieceLength + begin。 */
    public void writeBlock(int pieceIndex, int begin, byte[] block) throws IOException {
        checkBlock(pieceIndex, begin, block);
        channel.write(ByteBuffer.wrap(block), pieceOffset(pieceIndex) + begin);
    }

    /** 读回整个 Piece 计算 SHA-1，与种子的分片哈希比对。 */
    public boolean verifyPiece(int pieceIndex) throws IOException {
        checkPieceIndex(pieceIndex);
        int length = pieceLengthOf(pieceIndex);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long offset = pieceOffset(pieceIndex);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) {
                throw new IOException("unexpected end of " + partFile + " while verifying piece " + pieceIndex);
            }
        }
        buffer.flip();
        byte[] actual = sha1(buffer);
        return Arrays.equals(actual, meta.pieceHash(pieceIndex));
    }

    /** Piece 校验失败后清零该区间，供引擎重新调度下载。 */
    public void clearPiece(int pieceIndex) throws IOException {
        checkPieceIndex(pieceIndex);
        byte[] zeros = new byte[8192];
        long offset = pieceOffset(pieceIndex);
        long remaining = pieceLengthOf(pieceIndex);
        while (remaining > 0) {
            int chunk = (int) Math.min(zeros.length, remaining);
            channel.write(ByteBuffer.wrap(zeros, 0, chunk), offset);
            offset += chunk;
            remaining -= chunk;
        }
    }

    /** 全部 Piece 完成后：落盘并原子改名为最终文件名。 */
    public void finish() throws IOException {
        channel.force(true);
        channel.close();
        Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
    }

    public Path partFile() {
        return partFile;
    }

    public Path finalFile() {
        return finalFile;
    }

    public int pieceCount() {
        return meta.pieceCount();
    }

    public int pieceLengthOf(int pieceIndex) {
        checkPieceIndex(pieceIndex);
        long offset = pieceOffset(pieceIndex);
        return (int) Math.min(meta.pieceLength(), meta.length() - offset);
    }

    private long pieceOffset(int pieceIndex) {
        return pieceIndex * (long) meta.pieceLength();
    }

    private void checkPieceIndex(int pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= meta.pieceCount()) {
            throw new IllegalArgumentException("piece index " + pieceIndex
                + " out of [0," + meta.pieceCount() + ")");
        }
    }

    private void checkBlock(int pieceIndex, int begin, byte[] block) {
        checkPieceIndex(pieceIndex);
        if (begin < 0 || block.length == 0 || begin + (long) block.length > pieceLengthOf(pieceIndex)) {
            throw new IllegalArgumentException("block [" + begin + "," + (begin + block.length)
                + ") outside piece " + pieceIndex + " of length " + pieceLengthOf(pieceIndex));
        }
    }

    private static byte[] sha1(ByteBuffer data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(data);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-1", e);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
