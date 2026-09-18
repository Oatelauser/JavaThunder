package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.FileFilter;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;

import java.util.List;

/**
 * 选择性下载的件级投影（{@link FileFilter} → Piece 位图）：想要文件的字节区间覆盖到
 * 的 Piece 全部"必需"——v1 多文件 Piece 覆盖拼接流、可跨界，重叠即必需（含少量
 * 不需要的字节，协议粒度所限）；v2 实文件按件对齐，投影即精确件集。单文件种子对
 * 文件名判定一次（files 为空是其形态）。
 *
 * <p>完成语义与进度分母随之换基：全部必需 Piece 落定即完成；fraction/ETA/announce
 * left 以 wantedBytes 计。过滤器排除一切时构造期即拒绝（任务无意义，早失败早改）。
 */
final class WantedPieces {

    private final Bitfield required;
    private final long wantedBytes;
    private final long totalLength;
    private final long pieceLength;

    WantedPieces(TorrentMetadata meta, FileFilter filter) {
        int pieceCount = meta.pieceCount();
        this.required = new Bitfield(pieceCount);
        this.totalLength = meta.length();
        this.pieceLength = meta.pieceLength();
        if (meta.multiFile()) {
            for (TorrentMetadata.TorrentFile file : meta.files()) {
                if (!file.padding() && file.length() > 0 && filter.wanted(file.path())) {
                    markRange(file.offset(), file.length());
                }
            }
        } else if (totalLength > 0 && filter.wanted(List.of(meta.name()))) {
            markRange(0, totalLength); // 单文件：文件名即根名，一次判定
        }
        if (required.cardinality() == 0) {
            throw new IllegalArgumentException(
                    "file filter excludes every file of torrent " + meta.name());
        }
        this.wantedBytes = sumWantedBytes();
    }

    /** 流偏移区间 [offset, offset+len) 覆盖到的 Piece 全部置必需。 */
    private void markRange(long offset, long length) {
        int first = (int) (offset / pieceLength);
        int last = (int) ((offset + length - 1) / pieceLength);
        for (int piece = first; piece <= last; piece++) {
            required.set(piece);
        }
    }

    private long sumWantedBytes() {
        long bytes = 0;
        for (int i = 0; i < required.size(); i++) {
            if (required.has(i)) {
                bytes += Math.min(pieceLength, totalLength - (long) i * pieceLength);
            }
        }
        return bytes;
    }

    /** 该 Piece 是否参与下载与完成判定。 */
    boolean requiredPiece(int piece) {
        return piece >= 0 && piece < required.size() && required.has(piece);
    }

    /** 全部必需 Piece 均已落定（完成判定；resume 里残留的非必需位不干扰）。 */
    boolean completeAgainst(Bitfield local) {
        for (int i = 0; i < required.size(); i++) {
            if (required.has(i) && !local.has(i)) {
                return false;
            }
        }
        return true;
    }

    /** 进度/ETA/announce left 的分母（跨界件整件计入，末件按实际长度截断）。 */
    long wantedBytes() {
        return wantedBytes;
    }
}
