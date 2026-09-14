package io.github.oatelauser.thunder.api;

/** 某一时刻的任务进度（事件节流约 500ms 一帧）。 */
public record ProgressSnapshot(
    double fraction,
    long downloadedBytes,
    long uploadedBytes,
    long downloadRateBps,
    long uploadRateBps,
    int connectedPeers,
    double availability) {
}
