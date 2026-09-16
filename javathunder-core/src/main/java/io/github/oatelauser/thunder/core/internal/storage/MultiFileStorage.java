package io.github.oatelauser.thunder.core.internal.storage;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 多文件存储（B3，DESIGN §6.6）：Piece 覆盖文件的拼接字节流（可跨文件边界）。
 * 下载期写入 {@code <name>.part/00000..N} 编号文件，finish 时按种子路径落位改名——
 * 编号中间形态避免半成品文件以真实名字出现在目标目录。
 */
public final class MultiFileStorage implements TorrentStorage {

    private final TorrentMetadata meta;
    private final Path rootDir;
    private final Path partRoot;
    /** seed-only 导入模式：工作路径=最终路径，finish() 的 move 跳过。 */
    private final boolean importMode;
    private final FileChannel[] channels;
    private final TorrentMetadata.TorrentFile[] files;

    public MultiFileStorage(TorrentMetadata meta, Path targetDir) throws IOException {
        this(meta, targetDir, false);
    }

    /**
     * @param adoptExistingData seed-only（G2 导入）为 true：目录树已在最终位置且无暂存目录
     *                          时直接以最终路径为工作对象（跳过预分配，finish 不 move/清理）。
     *                          下载会话必须 false——保持既有 .part 暂存语义。
     */
    public MultiFileStorage(TorrentMetadata meta, Path targetDir, boolean adoptExistingData)
            throws IOException {
        // seed-only 导入：目录树已在最终位置且无暂存目录——直接以最终路径为工作对象
        this.importMode = adoptExistingData
                && !Files.exists(targetDir.resolve(meta.name() + ".part"))
                && Files.isDirectory(targetDir.resolve(meta.name()));
        this.meta = meta;
        Files.createDirectories(targetDir);
        this.rootDir = targetDir.resolve(meta.name());
        this.partRoot = targetDir.resolve(meta.name() + ".part");
        this.files = meta.files().toArray(new TorrentMetadata.TorrentFile[0]);
        this.channels = new FileChannel[files.length];
        try {
            for (int i = 0; i < files.length; i++) {
                if (files[i].length() == 0) {
                    continue; // 空文件：finish 时直接创建占位
                }
                Path staged = stagedPath(i);
                Files.createDirectories(staged.getParent());
                channels[i] = FileChannel.open(staged,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                if (!importMode) {
                    channels[i].write(ByteBuffer.wrap(new byte[1]), files[i].length() - 1); // 稀疏预分配
                }
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly();
            throw e;
        }
    }

    @Override
    public void writeBlock(int pieceIndex, int begin, byte[] block) throws IOException {
        scatterWrite(pieceIndex * meta.pieceLength() + begin, List.of(block));
    }

    @Override
    public void writePieceBuffers(int pieceIndex, ByteBuffer[] buffers) throws IOException {
        List<byte[]> chunks = new ArrayList<>(buffers.length);
        for (ByteBuffer buffer : buffers) {
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            chunks.add(chunk);
        }
        scatterWrite(pieceIndex * meta.pieceLength(), chunks);
    }

    @Override
    public boolean verifyPiece(int pieceIndex) throws IOException {
        MessageDigest digest = sha1();
        byte[] buffer = new byte[64 * 1024];
        long remaining = pieceLengthOf(pieceIndex);
        int fileIndex = fileIndexFor(pieceIndex * meta.pieceLength());
        long fileOffset = pieceIndex * meta.pieceLength() - files[fileIndex].offset();
        while (remaining > 0) {
            int chunk = (int) Math.min(Math.min(buffer.length, remaining),
                files[fileIndex].length() - fileOffset);
            ByteBuffer view = ByteBuffer.wrap(buffer, 0, chunk);
            while (view.hasRemaining()) {
                if (channels[fileIndex].read(view, fileOffset + view.position()) < 0) {
                    throw new IOException("unexpected eof verifying piece " + pieceIndex);
                }
            }
            digest.update(buffer, 0, chunk);
            fileOffset += chunk;
            remaining -= chunk;
            if (fileOffset >= files[fileIndex].length() && remaining > 0) {
                fileIndex++;
                fileOffset = 0;
            }
        }
        return MessageDigest.isEqual(digest.digest(), meta.pieceHash(pieceIndex));
    }

    @Override
    public void clearPiece(int pieceIndex) throws IOException {
        byte[] zeros = new byte[64 * 1024];
        long streamOffset = pieceIndex * meta.pieceLength();
        long remaining = pieceLengthOf(pieceIndex);
        while (remaining > 0) {
            int chunk = (int) Math.min(zeros.length, remaining);
            scatterWrite(streamOffset, List.of(Arrays.copyOf(zeros, chunk)));
            streamOffset += chunk;
            remaining -= chunk;
        }
    }

    @Override
    public byte[] readBlock(int pieceIndex, int begin, int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        int fileIndex = fileIndexFor(pieceIndex * meta.pieceLength() + begin);
        long fileOffset = pieceIndex * meta.pieceLength() + begin - files[fileIndex].offset();
        int remaining = length;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, files[fileIndex].length() - fileOffset);
            ByteBuffer buffer = ByteBuffer.allocate(chunk);
            while (buffer.hasRemaining()) {
                if (channels[fileIndex].read(buffer, fileOffset + buffer.position()) < 0) {
                    throw new IOException("unexpected eof serving piece " + pieceIndex);
                }
            }
            out.writeBytes(buffer.array());
            fileOffset += chunk;
            remaining -= chunk;
            if (fileOffset >= files[fileIndex].length() && remaining > 0) {
                fileIndex++;
                fileOffset = 0;
            }
        }
        return out.toByteArray();
    }

    @Override
    public int pieceCount() {
        return meta.pieceCount();
    }

    @Override
    public int pieceLengthOf(int pieceIndex) {
        long offset = pieceIndex * meta.pieceLength();
        return (int) Math.min(meta.pieceLength(), meta.length() - offset);
    }

    @Override
    public void finish() throws IOException {
        for (int i = 0; i < files.length; i++) {
            TorrentMetadata.TorrentFile file = files[i];
            Path finalPath = rootDir;
            for (String component : file.path()) {
                finalPath = finalPath.resolve(component);
            }
            Files.createDirectories(finalPath.getParent());
            if (file.length() == 0) {
                Files.writeString(finalPath, "");
                continue;
            }
            channels[i].force(true);
            channels[i].close();
            if (!importMode) {
                Files.move(stagedPath(i), finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!importMode) {
            deleteRecursively(partRoot);
        }
    }

    @Override
    public Path partFile() {
        return partRoot;
    }

    @Override
    public Path finalFile() {
        return rootDir;
    }

    @Override
    public void close() throws IOException {
        boolean anyFailure = false;
        for (FileChannel channel : channels) {
            if (channel != null && channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException e) {
                    anyFailure = true;
                }
            }
        }
        if (anyFailure) {
            throw new IOException("failure while closing multi-file channels");
        }
    }

    /** 拼接流偏移 scatter 写：一个缓冲可跨多个文件边界；0 字节文件无 channel，跳过其零长段。 */
    private void scatterWrite(long streamOffset, List<byte[]> chunks) throws IOException {
        int fileIndex = fileIndexFor(streamOffset);
        long fileOffset = streamOffset - files[fileIndex].offset();
        for (byte[] chunk : chunks) {
            int chunkOff = 0;
            while (chunkOff < chunk.length) {
                int writable = (int) Math.min(chunk.length - chunkOff,
                    files[fileIndex].length() - fileOffset);
                if (writable > 0) {
                    channels[fileIndex].write(ByteBuffer.wrap(chunk, chunkOff, writable), fileOffset);
                    fileOffset += writable;
                    chunkOff += writable;
                }
                if (fileOffset >= files[fileIndex].length() && chunkOff < chunk.length) {
                    fileIndex++;
                    fileOffset = 0;
                }
            }
        }
    }

    private Path stagedPath(int index) {
        if (importMode) {
            TorrentMetadata.TorrentFile file = files[index];
            Path finalPath = rootDir;
            for (String component : file.path()) {
                finalPath = finalPath.resolve(component);
            }
            return finalPath;
        }
        return partRoot.resolve(String.format("%05d", index));
    }

    private int fileIndexFor(long streamOffset) {
        // 文件按 offset 升序；找第一个"下一文件起点之前"的（空文件自然跳过）
        for (int i = 0; i < files.length - 1; i++) {
            if (streamOffset < files[i + 1].offset()) {
                return i;
            }
        }
        return files.length - 1;
    }

    private void closeQuietly() {
        for (FileChannel channel : channels) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-1", e);
        }
    }
}
