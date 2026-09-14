package io.github.oatelauser.thunder.core.internal.client;

import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.engine.DownloadSession;

import java.util.concurrent.CompletableFuture;

/** {@link DownloadTask} 的会话适配。 */
public final class DownloadTaskImpl implements DownloadTask {

    private final DownloadSession session;

    public DownloadTaskImpl(DownloadSession session, Runnable onFinished) {
        this.session = session;
        session.future().whenComplete((result, error) -> onFinished.run());
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
