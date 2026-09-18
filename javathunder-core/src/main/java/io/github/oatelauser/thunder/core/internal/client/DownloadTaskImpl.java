package io.github.oatelauser.thunder.core.internal.client;

import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.engine.DownloadSession;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link DownloadTask} 的会话适配。
 */
public final class DownloadTaskImpl implements DownloadTask {

    private final DownloadSession session;

    public DownloadTaskImpl(DownloadSession session, Runnable onFinished) {
        this.session = session;
        // future 完成＝下载阶段结束，但 seedAfterComplete 任务此后仍以 SEEDING 存活
        // （入站握手路由、announce、上传服务都要继续）。若此时就注销路由/释放并发槽，
        // 做种客户端对外表现为"完成即下线"（A1 互操作：ttorrent 连我方监听端口握手即 EOF）。
        // 因此：非做种任务在 future 完成时终止；做种任务延迟到真正终止（COMPLETED/CANCELLED/FAILED）。
        Runnable finishOnce = once(onFinished);
        session.future().whenComplete((result, error) -> finishWhenSessionEnds(finishOnce));
    }

    private void finishWhenSessionEnds(Runnable finishOnce) {
        if (session.state() != TaskState.SEEDING) {
            finishOnce.run();
            return;
        }
        session.addListener(new TaskListener() {
            @Override
            public void onStateChanged(TaskState from, TaskState to) {
                if (to == TaskState.COMPLETED || to == TaskState.CANCELLED || to == TaskState.FAILED) {
                    finishOnce.run();
                }
            }
        });
    }

    private static Runnable once(Runnable action) {
        AtomicBoolean finished = new AtomicBoolean();
        return () -> {
            if (finished.compareAndSet(false, true)) {
                action.run();
            }
        };
    }

    @Override
    public CompletableFuture<DownloadResult> future() {
        return session.future();
    }

    @Override
    public TaskState state() {
        return session.state();
    }

    @Override
    public ProgressSnapshot snapshot() {
        return session.snapshot();
    }

    @Override
    public void addListener(TaskListener listener) {
        session.addListener(listener);
    }

    @Override
    public void pause() {
        session.pause();
    }

    @Override
    public void resume() {
        session.resume();
    }

    @Override
    public void cancel(boolean deleteData) {
        session.cancel(deleteData);
    }
}
