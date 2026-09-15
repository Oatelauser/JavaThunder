package io.github.oatelauser.thunder.api;

import org.jspecify.annotations.Nullable;

/**
 * 某一时刻的任务进度（事件节流约 500ms 一帧）。
 *
 * @param downloadRateBps / uploadRateBps EMA 平滑后的速率（字节/秒）
 * @param etaMillis        预计剩余时间；速率为 0 或已完成时为 null
 */
public record ProgressSnapshot(
    double fraction,
    long downloadedBytes,
    long uploadedBytes,
    long downloadRateBps,
    long uploadRateBps,
    int connectedPeers,
    double availability,
    @Nullable Long etaMillis) {
}
