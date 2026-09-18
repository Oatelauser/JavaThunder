package io.github.oatelauser.thunder.core.internal.metainfo;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/**
 * 种子元数据（v1/hybrid/v2）：文件清单、Piece 大小、Tracker 分层。
 * {@code infoHash} 恒为 20 字节——v1/hybrid 为 info 字典原始字节的 SHA-1，
 * v2-only 为 SHA-256 截断前 20 字节（满足 DHT/线协议的 v1 宽度）；
 * 完整 32 字节 SHA-256 只在 v2/hybrid 形态下放 {@code infoHashV2}。
 *
 * <p>多文件种子（B3）：{@code files} 非空，{@code length} = 全部文件长度之和，
 * Piece 覆盖文件的拼接字节流（可跨文件边界）；{@code name} 为根目录名。
 *
 * <p>WebSeed（BEP 19）：{@code webSeeds} 为顶层 url-list 归一化的 HTTP 兜底源列表，
 * 空列表表示无。磁力路径（元数据来自 BEP 9 的裸 info 字典）天然为空——url-list
 * 是顶层字段，不在 info 内。
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
        List<TorrentFile> files,
        List<String> webSeeds,
        TorrentVersion version,
        @Nullable byte[] infoHashV2) {

    /** V1 形态兼容构造（v2 字段缺省）。 */
    public TorrentMetadata(byte[] infoHash, @Nullable String announce,
            List<List<String>> announceList, @Nullable String comment, @Nullable String createdBy,
            @Nullable Long creationDateSec, String name, long length, long pieceLength,
            byte[] pieces, boolean privateFlag, List<TorrentFile> files, List<String> webSeeds) {
        this(infoHash, announce, announceList, comment, createdBy, creationDateSec, name,
                length, pieceLength, pieces, privateFlag, files, webSeeds, TorrentVersion.V1, null);
    }

    /**
     * 多文件种子中的一个文件：相对根目录的路径 + 在拼接字节流中的偏移。
     * v2/hybrid：{@code piecesRoot} 为该文件 Merkle 根（V1 为 null）；
     * {@code padding} 为 BEP 47 填充文件（全零流段，finish 落位时跳过物化）。
     */
    public record TorrentFile(List<String> path, long offset, long length,
            @Nullable byte[] piecesRoot, boolean padding,
            @Nullable byte[] pieceLayer) {

        /** V1 形态兼容构造。 */
        public TorrentFile(List<String> path, long offset, long length) {
            this(path, offset, length, null, false, null);
        }

        /** v2 无独立层带文件（单 piece 文件）的兼容构造。 */
        public TorrentFile(List<String> path, long offset, long length,
                @Nullable byte[] piecesRoot, boolean padding) {
            this(path, offset, length, piecesRoot, padding, null);
        }
    }

    /**
     * 磁力链接路径（B1）：从 BEP 9 拉到的裸 info 字典字节构造（调用方已校验哈希——
     * v1/hybrid 为 SHA-1，v2-only 为截断 20 字节 SHA-256，见 MetadataFetcher）。
     * announce 侧由磁力的 tr 参数补齐，单层。
     */
    public static TorrentMetadata fromInfoDict(byte[] infoBytes, List<String> trackers) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(infoBytes);
            BencodeValue value = Bencode.decodeValue(buf);
            if (!(value instanceof BDict info)) {
                throw new IllegalArgumentException("info dict must be a bencoded dict");
            }
            // info-hash 按形态推导：v2-only（有 file tree 无 pieces）= 截断 SHA-256，
            // 其余 = SHA-1（与线协议握手用的 20 字节身份一致）
            boolean v2Only = info.value().containsKey(BString.of("file tree"))
                    && !info.value().containsKey(BString.of("pieces"));
            byte[] digest = MessageDigest.getInstance(v2Only ? "SHA-256" : "SHA-1").digest(infoBytes);
            byte[] infoHash = v2Only ? Arrays.copyOf(digest, 20) : digest;
            // 复用 TorrentParser 的字段校验：把它当 .torrent 的 info 段解析
            return TorrentParser.buildFromInfoDict(info, infoHash, trackers);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid info dict: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public TorrentMetadata {
        announceList = List.copyOf(announceList);
        pieces = pieces.clone();
        files = List.copyOf(files);
        webSeeds = List.copyOf(webSeeds);
        infoHashV2 = infoHashV2 == null ? null : infoHashV2.clone();
    }

    public boolean multiFile() {
        return !files.isEmpty();
    }

    public int pieceCount() {
        // V2 无 pieces 数组，按布局推导；V1/HYBRID 以 v1 哈希数为准（混合侧两者必相等，构造期已校验）
        return version == TorrentVersion.V2
                ? (int) ((length + pieceLength - 1) / pieceLength)
                : pieces.length / 20;
    }

    /** v1 逐件 SHA-1（V2 形态无此概念——校验走 Merkle 层带，见 BEP 52 实施的 S3）。 */
    public byte[] pieceHash(int index) {
        if (version == TorrentVersion.V2) {
            throw new UnsupportedOperationException("v2 torrents verify via merkle piece layers");
        }
        return Arrays.copyOfRange(pieces, index * 20, index * 20 + 20);
    }

    /**
     * 实际可用的 Tracker 分层：announce-list 优先，否则由 announce 组成单层。
     */
    public List<List<String>> trackerTiers() {
        if (!announceList.isEmpty()) {
            return announceList;
        }
        return announce == null ? List.of() : List.of(List.of(announce));
    }
}
