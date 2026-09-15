package io.github.oatelauser.thunder.api;

import org.jspecify.annotations.Nullable;

/**
 * 任务事件监听器。回调运行在专用事件线程（或注入的 Executor）上，
 * 绝不运行在 Peer 协议线程；回调抛出的异常会被吞掉，不影响协议。
 */
public interface TaskListener {

    default void onStateChanged(TaskState from, TaskState to) {
    }

    default void onProgress(ProgressSnapshot snapshot) {
    }

    default void onPieceComplete(int pieceIndex) {
    }

    /** 一次 announce 的结果（成功或失败原因）。 */
    default void onTrackerAnnounce(String trackerUrl, @Nullable String failureReason,
                                   int seeders, int leechers) {
    }

    default void onPeerConnected(String peerAddress) {
    }

    default void onPeerDisconnected(String peerAddress, @Nullable String reason) {
    }

    default void onError(Throwable error) {
    }
}
