package io.github.oatelauser.thunder.api;

import java.util.concurrent.CompletableFuture;

/**
 * 一个下载任务的句柄。所有方法线程安全。
 */
public interface DownloadTask {

    CompletableFuture<DownloadResult> future();

    TaskState state();

    ProgressSnapshot snapshot();

    void addListener(TaskListener listener);

    void pause();

    void resume();

    /**
     * @param deleteData true 时连同本地数据与状态文件一并删除。
     */
    void cancel(boolean deleteData);
}
