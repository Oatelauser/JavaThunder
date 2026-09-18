package io.github.oatelauser.thunder.api;

/**
 * 下载任务状态机（DESIGN §3.3）。终态：COMPLETED / FAILED / CANCELLED
 * （SEEDING 经 pause/cancel 离开）。
 */
public enum TaskState {
    /** 排队中：等待并发槽位，或磁力任务的元数据获取阶段。 */
    QUEUED,
    /** 重启校验中：断点续传启动时对已有数据按档位校验（RestartVerifyMode）。 */
    VERIFYING,
    /** 下载中：含 Peer 请求、WebSeed 取回与逐件校验。 */
    DOWNLOADING,
    /** 做种中：完整持有数据并对外供块（seed() 直入或下载完成后转入）。 */
    SEEDING,
    /** 完成：非做种任务的终态，future() 已以 DownloadResult 完成。 */
    COMPLETED,
    /** 已暂停：可 resume 回原态（下载↔下载、做种↔做种）。 */
    PAUSED,
    /** 失败：不可恢复错误的终态（校验不一致、存储故障、元数据超时等）。 */
    FAILED,
    /** 已取消：用户主动取消的终态。 */
    CANCELLED
}
