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
public final class StorageManager implements TorrentStorage {

    /** 写通道池大小：Windows 上单句柄位置写会串行化，分片解除多 Peer 并发写的互斥。 */
    private static final int WRITE_CHANNELS = 4;

    private final TorrentMetadata meta;
    private final Path partFile;
    private final Path finalFile;
    private final FileChannel[] channels;

    /** 实际工作文件：seed-only 导入时数据已在最终名（无 .part），直接以它为对象。 */
    private final Path workFile;

    public StorageManager(TorrentMetadata meta, Path targetDir) throws IOException {
        this(meta, targetDir, false);
    }

    /**
     * @param adoptExistingData seed-only（G2 导入）为 true：数据已在最终名且无 .part、
     *                          长度相符时以最终文件为工作对象（跳过预分配，finish 不改名）。
     *                          下载会话必须 false——保持既有 .part 语义（断点伪造、
     *                          重下覆盖都以其为工作文件）。
     */
    public StorageManager(TorrentMetadata meta, Path targetDir, boolean adoptExistingData)
            throws IOException {
        this.meta = meta;
        Files.createDirectories(targetDir);
        this.finalFile = targetDir.resolve(meta.name());
        this.partFile = targetDir.resolve(meta.name() + ".part");
        this.workFile = adoptExistingData && !Files.exists(partFile) && Files.exists(finalFile)
                && Files.size(finalFile) == meta.length() ? finalFile : partFile;
        this.channels = new FileChannel[WRITE_CHANNELS];
        for (int i = 0; i < WRITE_CHANNELS; i++) {
            channels[i] = FileChannel.open(workFile,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
        if (workFile == partFile) {
            // 预分配（仅全新下载）：截长防残留，末位写一字节撑出全尺寸（稀疏）
            channels[0].truncate(meta.length());
            if (meta.length() > 0) {
                channels[0].write(ByteBuffer.wrap(new byte[1]), meta.length() - 1);
            }
        }
    }

    private FileChannel channelFor(int pieceIndex) {
        return channels[Math.floorMod(pieceIndex, channels.length)];
    }

    /** Block 落盘：写入位置 = pieceIndex × pieceLength + begin。 */
    public void writeBlock(int pieceIndex, int begin, byte[] block) throws IOException {
        checkBlock(pieceIndex, begin, block);
        channelFor(pieceIndex).write(ByteBuffer.wrap(block), pieceOffset(pieceIndex) + begin);
    }

    /** 整 Piece 的块序列 gather 直写（引擎零拷贝组装路径：块引用即缓冲区）。 */
    public void writePieceBuffers(int pieceIndex, ByteBuffer[] buffers) throws IOException {
        int expected = pieceLengthOf(pieceIndex);
        long total = 0;
        for (ByteBuffer buffer : buffers) {
            total += buffer.remaining();
        }
        if (total != expected) {
            throw new IllegalArgumentException("piece " + pieceIndex + " expects "
                + expected + " bytes, got " + total);
        }
        FileChannel channel = channelFor(pieceIndex);
        // gather 写基于通道自身 position：设位 + 通道监视器防并发串位（池内 4 通道仍并行）
        synchronized (channel) {
            channel.position(pieceOffset(pieceIndex));
            while (true) {
                long remaining = 0;
                for (ByteBuffer buffer : buffers) {
                    remaining += buffer.remaining();
                }
                if (remaining == 0) {
                    return;
                }
                channel.write(buffers, 0, buffers.length);
            }
        }
    }

    /** 读回整个 Piece 计算 SHA-1，与种子的分片哈希比对。 */
    public boolean verifyPiece(int pieceIndex) throws IOException {
        checkPieceIndex(pieceIndex);
        int length = pieceLengthOf(pieceIndex);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long offset = pieceOffset(pieceIndex);
        while (buffer.hasRemaining()) {
            if (channelFor(pieceIndex).read(buffer, offset + buffer.position()) < 0) {
                throw new IOException("unexpected end of " + partFile + " while verifying piece " + pieceIndex);
            }
        }
        buffer.flip();
        byte[] actual = sha1(buffer);
        return Arrays.equals(actual, meta.pieceHash(pieceIndex));
    }

    /** 读取一个 Block（上传服务用）。 */
    public byte[] readBlock(int pieceIndex, int begin, int length) throws IOException {
        checkPieceIndex(pieceIndex);
        if (begin < 0 || length <= 0 || begin + (long) length > pieceLengthOf(pieceIndex)) {
            throw new IllegalArgumentException("read [" + begin + "," + (begin + length)
                + ") outside piece " + pieceIndex + " of length " + pieceLengthOf(pieceIndex));
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channelFor(pieceIndex).read(buffer, pieceOffset(pieceIndex) + begin + buffer.position()) < 0) {
                throw new IOException("unexpected end of " + partFile + " serving piece " + pieceIndex);
            }
        }
        return buffer.array();
    }

    /** Piece 校验失败后清零该区间，供引擎重新调度下载。 */
    public void clearPiece(int pieceIndex) throws IOException {
        checkPieceIndex(pieceIndex);
        byte[] zeros = new byte[8192];
        long offset = pieceOffset(pieceIndex);
        long remaining = pieceLengthOf(pieceIndex);
        while (remaining > 0) {
            int chunk = (int) Math.min(zeros.length, remaining);
            channelFor(pieceIndex).write(ByteBuffer.wrap(zeros, 0, chunk), offset);
            offset += chunk;
            remaining -= chunk;
        }
    }

    /** 全部 Piece 完成后：落盘并原子改名为最终文件名。 */
    public void finish() throws IOException {
        for (FileChannel channel : channels) {
            channel.force(true);
            channel.close();
        }
        if (!workFile.equals(finalFile)) {
            Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
        }
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
        for (FileChannel channel : channels) {
            channel.close();
        }
    }
}
