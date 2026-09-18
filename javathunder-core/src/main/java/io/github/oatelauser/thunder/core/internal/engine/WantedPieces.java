package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.FileFilter;
import io.github.oatelauser.thunder.api.FilePriority;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;

import java.util.List;

/**
 * 选择性下载与文件优先级的件级投影（{@link FileFilter} × {@link FilePriority} →
 * 逐件优先级数组）：过滤器先决定取舍（wanted），优先级在保留集内给次序——一件压住
 * 多个保留文件时取最高优先级（跨界件必须整件下载，取高者不拖累高优先进度）；
 * SKIP（0）= 不参与下载与完成判定，与过滤器排除等效（两者交集语义）。v1 多文件
 * Piece 覆盖拼接流、可跨界；v2 实文件按件对齐，投影即精确件集。单文件种子对文件名
 * 判定一次（files 为空是其形态）。
 *
 * <p>完成语义与进度分母随之换基：全部优先级 &gt; 0 的件落定即完成；fraction/ETA/
 * announce left 以 wantedBytes 计。过滤且优先级全零时构造期拒绝（任务无意义）。
 */
final class WantedPieces {

    private final int[] priority;
    private final long wantedBytes;
    private final long totalLength;
    private final long pieceLength;

    WantedPieces(TorrentMetadata meta, FileFilter filter, FilePriority priorities) {
        int pieceCount = meta.pieceCount();
        this.priority = new int[pieceCount];
        this.totalLength = meta.length();
        this.pieceLength = meta.pieceLength();
        if (meta.multiFile()) {
            for (TorrentMetadata.TorrentFile file : meta.files()) {
                if (!file.padding() && file.length() > 0 && filter.wanted(file.path())) {
                    markRange(file.offset(), file.length(),
                            Math.max(0, priorities.priority(file.path())));
                }
            }
        } else if (totalLength > 0 && filter.wanted(List.of(meta.name()))) {
            markRange(0, totalLength, Math.max(0, priorities.priority(List.of(meta.name()))));
        }
        int required = 0;
        for (int p : priority) {
            if (p > 0) {
                required++;
            }
        }
        if (required == 0) {
            throw new IllegalArgumentException(
                    "file filter/priorities exclude every piece of torrent " + meta.name());
        }
        this.wantedBytes = sumWantedBytes();
    }

    /** 流偏移区间 [offset, offset+len) 覆盖到的 Piece 记最高优先级。 */
    private void markRange(long offset, long length, int level) {
        int first = (int) (offset / pieceLength);
        int last = (int) ((offset + length - 1) / pieceLength);
        for (int piece = first; piece <= last; piece++) {
            priority[piece] = Math.max(priority[piece], level);
        }
    }

    private long sumWantedBytes() {
        long bytes = 0;
        for (int i = 0; i < priority.length; i++) {
            if (priority[i] > 0) {
                bytes += Math.min(pieceLength, totalLength - (long) i * pieceLength);
            }
        }
        return bytes;
    }

    /** 该 Piece 是否参与下载与完成判定（优先级 > 0）。 */
    boolean requiredPiece(int piece) {
        return piece >= 0 && piece < priority.length && priority[piece] > 0;
    }

    /** 该 Piece 的优先级（0 = 不需要；选件字典序的第一键）。 */
    int priorityOf(int piece) {
        return piece >= 0 && piece < priority.length ? priority[piece] : 0;
    }

    /** 全部必需 Piece 均已落定（完成判定；resume 里残留的非必需位不干扰）。 */
    boolean completeAgainst(Bitfield local) {
        for (int i = 0; i < priority.length; i++) {
            if (priority[i] > 0 && !local.has(i)) {
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
