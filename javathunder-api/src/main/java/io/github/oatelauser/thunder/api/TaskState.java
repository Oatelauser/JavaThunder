package io.github.oatelauser.thunder.api;

/**
 * 下载任务状态机（DESIGN §3.3）。
 */
public enum TaskState {
    QUEUED,
    VERIFYING,
    DOWNLOADING,
    SEEDING,
    COMPLETED,
    PAUSED,
    FAILED,
    CANCELLED
}
