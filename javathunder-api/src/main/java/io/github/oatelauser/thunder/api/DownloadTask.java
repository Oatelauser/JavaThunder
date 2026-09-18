package io.github.oatelauser.thunder.api;

import java.util.concurrent.CompletableFuture;

/**
 * 一个下载任务的句柄。所有方法线程安全。
 *
 * <p>生命周期：QUEUED → DOWNLOADING →（PAUSED ⇄ DOWNLOADING）→
 * COMPLETED / SEEDING / FAILED / CANCELLED（见 {@link TaskState}）。
 */
public interface DownloadTask {

    /**
     * 完成未来：正常完成为 {@link DownloadResult}；任务失败为异常完成
     * （IllegalStateException / IOException / 校验失败等）；cancel 为 CancellationException。
     * 做种任务（seedAfterComplete 或 client.seed）在 SEEDING 态不会完成——用
     * {@link #cancel(boolean)} 或 client.close() 停止。
     */
    CompletableFuture<DownloadResult> future();

    /** 当前状态（线程安全的弱一致快照）。 */
    TaskState state();

    /** 进度快照（事件节流约 500ms 一帧；磁力元数据阶段 fraction 为 0 属正常）。 */
    ProgressSnapshot snapshot();

    /** 追加任务事件监听（回调线程见 {@link TaskListener}；可多个，重复添加会重复回调）。 */
    void addListener(TaskListener listener);

    /** 暂停：停止请求与 announce，保留已下数据与连接句柄；做种任务暂停后仍是 SEEDING 恢复语义。 */
    void pause();

    /** 恢复暂停的任务；非 PAUSED 态调用为无操作。 */
    void resume();

    /**
     * 取消任务。
     *
     * @param deleteData true 时连同本地数据与状态文件一并删除。
     */
    void cancel(boolean deleteData);
}
