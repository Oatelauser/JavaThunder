package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.MagnetUri;
import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 磁力下载任务（B1）：两段式——元数据 future 就绪后惰性创建真实会话，
 * 对外表现为一个连续的 DownloadTask。状态在元数据阶段为 FETCHING_METADATA
 * 映射到 QUEUED（api 枚举无此中间态，复用最接近语义）。
 */
public final class MagnetDownloadTask implements DownloadTask {

    private static final Logger log = LoggerFactory.getLogger(MagnetDownloadTask.class);

    private final CompletableFuture<TorrentMetadata> metadataFuture;
    private final MagnetUri magnet;
    private final DownloadOptions options;
    private final SessionStarter starter;
    private final Runnable slotRelease;
    private final Executor eventExecutor;
    private final CompletableFuture<DownloadResult> result = new CompletableFuture<>();
    private final List<TaskListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<@Nullable DownloadTask> delegate = new AtomicReference<>();

    @FunctionalInterface
    public interface SessionStarter {
        DownloadTask start(TorrentMetadata meta, DownloadOptions options);
    }

    public MagnetDownloadTask(CompletableFuture<TorrentMetadata> metadataFuture, MagnetUri magnet,
            DownloadOptions options, SessionStarter starter, Runnable slotRelease,
            Executor eventExecutor) {
        this.metadataFuture = metadataFuture;
        this.magnet = magnet;
        this.options = options;
        this.starter = starter;
        this.slotRelease = slotRelease;
        this.eventExecutor = eventExecutor;
        metadataFuture.whenComplete((meta, error) -> {
            if (error != null) {
                onMetadataFailed(error);
                return;
            }
            // 元数据完成可能发生在对端传输线程（NIO selector）上：先切回事件线程，
            // 再做槽位操作与建会话——acquire 可能阻塞，绝不能停在传输线程里。
            eventExecutor.execute(() -> {
                if (result.isCancelled()) {
                    return; // cancel 先于会话创建：槽位已由 cancel 路径归还
                }
                // 元数据阶段结束：先还磁力槽位，再让 startSession 按普通下载取自己的槽。
                // 若双持有，N 个并发磁力任务在元数据成功后会互相等对方放槽——永久死锁。
                slotRelease.run();
                try {
                    DownloadTask task = starter.start(meta, options);
                    delegate.set(task);
                    for (TaskListener listener : listeners) {
                        task.addListener(listener);
                    }
                    task.future().whenComplete((r, e) -> {
                        if (e != null) {
                            result.completeExceptionally(e);
                        } else {
                            result.complete(r);
                        }
                    });
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        });
    }

    /**
     * 元数据阶段失败/取消的分流：用户取消保持取消终态（调用方要能区分"取消"与
     * "失败"，wrap 成 IllegalStateException 会抹掉该语义）；其余异常归一为
     * IllegalStateException 并归还磁力槽位。取消路径不在此释放槽位——cancel()
     * 的后续分支（delegate 尚未建）会释放，避免双释放依赖 releaseOnce 的幂等。
     */
    private void onMetadataFailed(Throwable error) {
        if (isCancellation(error)) {
            result.cancel(true);
            return;
        }
        slotRelease.run();
        result.completeExceptionally(wrap(error));
    }

    /** 取消可能裸达（本 future 被 cancel）也可能包在 CompletionException 里（上游链传播）。 */
    private static boolean isCancellation(Throwable error) {
        if (error instanceof CancellationException) {
            return true;
        }
        return error instanceof CompletionException cause && cause.getCause() instanceof CancellationException;
    }

    private static Throwable wrap(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return new IllegalStateException("magnet metadata fetch failed: " + cause.getMessage(), cause);
    }

    @Override
    public CompletableFuture<DownloadResult> future() {
        return result;
    }

    @Override
    public TaskState state() {
        DownloadTask task = delegate.get();
        return task != null ? task.state() : TaskState.QUEUED;
    }

    @Override
    public ProgressSnapshot snapshot() {
        DownloadTask task = delegate.get();
        return task != null ? task.snapshot()
                : new ProgressSnapshot(0, 0, 0, 0, 0, 0, 0, null);
    }

    @Override
    public void addListener(TaskListener listener) {
        listeners.add(listener);
        DownloadTask task = delegate.get();
        if (task != null) {
            task.addListener(listener);
        }
    }

    @Override
    public void pause() {
        DownloadTask task = delegate.get();
        if (task != null) {
            task.pause();
        }
    }

    @Override
    public void resume() {
        DownloadTask task = delegate.get();
        if (task != null) {
            task.resume();
        }
    }

    @Override
    public void cancel(boolean deleteData) {
        metadataFuture.cancel(true);
        DownloadTask task = delegate.get();
        if (task != null) {
            task.cancel(deleteData);
        } else {
            slotRelease.run();
        }
        result.cancel(true);
    }
}
