package io.github.oatelauser.thunder.core.internal.metainfo;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /**
     * 磁力链接路径（B1）：从 BEP 9 拉到的裸 info 字典字节构造（调用方已校验
     * SHA-1 == info-hash）。announce 侧由磁力的 tr 参数补齐，单层。
     */
    public static TorrentMetadata fromInfoDict(byte[] infoBytes, List<String> trackers) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(infoBytes);
            BencodeValue value = Bencode.decodeValue(buf);
            if (!(value instanceof BDict info)) {
                throw new IllegalArgumentException("info dict must be a bencoded dict");
            }
            byte[] infoHash;
            try {
                infoHash = MessageDigest.getInstance("SHA-1").digest(infoBytes);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            // 复用 TorrentParser 的字段校验：把它当 .torrent 的 info 段解析
            return TorrentParser.buildFromInfoDict(info, infoHash, trackers);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid info dict: " + e.getMessage(), e);
        }
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
