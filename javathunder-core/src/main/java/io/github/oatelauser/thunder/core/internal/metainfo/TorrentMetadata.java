package io.github.oatelauser.thunder.core.internal.metainfo;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * v1 种子元数据（BEP 3）：文件清单、Piece 大小、逐 Piece SHA-1、Tracker 分层。
 * {@code infoHash} 恒等于 info 字典原始字节的 SHA-1。
 *
 * <p>多文件种子（B3）：{@code files} 非空，{@code length} = 全部文件长度之和，
 * Piece 覆盖文件的拼接字节流（可跨文件边界）；{@code name} 为根目录名。
 */
public record TorrentMetadata(
    byte[] infoHash,
    @Nullable String announce,
    List<List<String>> announceList,
    @Nullable String comment,
    @Nullable String createdBy,
    @Nullable Long creationDateSec,
    String name,
    long length,
    long pieceLength,
    byte[] pieces,
    boolean privateFlag,
    List<TorrentFile> files) {

    /** 多文件种子中的一个文件：相对根目录的路径 + 在拼接字节流中的偏移。 */
    public record TorrentFile(List<String> path, long offset, long length) {
    }

    public TorrentMetadata {
        announceList = List.copyOf(announceList);
        pieces = pieces.clone();
        files = List.copyOf(files);
    }

    public boolean multiFile() {
        return !files.isEmpty();
    }

    public int pieceCount() {
        return pieces.length / 20;
    }

    public byte[] pieceHash(int index) {
        return Arrays.copyOfRange(pieces, index * 20, index * 20 + 20);
    }

    /** 实际可用的 Tracker 分层：announce-list 优先，否则由 announce 组成单层。 */
    public List<List<String>> trackerTiers() {
        if (!announceList.isEmpty()) {
            return announceList;
        }
        return announce == null ? List.of() : List.of(List.of(announce));
    }
}
