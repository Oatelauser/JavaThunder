package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 任务事件扇出：监听回调固定在调用方注入的事件执行器上运行（不占用 selector /
 * worker 线程）；单个监听器抛出的 {@link RuntimeException} 被吞掉，不影响其余
 * 监听器，也不影响下载主流程。
 */
final class TaskEventDispatcher {

    private final CopyOnWriteArrayList<TaskListener> listeners;
    private final Executor eventExecutor;

    TaskEventDispatcher(CopyOnWriteArrayList<TaskListener> listeners, Executor eventExecutor) {
        this.listeners = listeners;
        this.eventExecutor = eventExecutor;
    }

    void add(TaskListener listener) {
        listeners.add(listener);
    }

    /** 逐监听器投递到事件执行器；单监听器异常就地吞掉（见类注释）。 */
    private void dispatch(Consumer<TaskListener> call) {
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    call.accept(listener);
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    void error(Throwable error) {
        dispatch(listener -> listener.onError(error));
    }

    void trackerAnnounce(String url, @Nullable String failure, int seeders, int leechers) {
        dispatch(listener -> listener.onTrackerAnnounce(url, failure, seeders, leechers));
    }

    void peerConnected(String key) {
        dispatch(listener -> listener.onPeerConnected(key));
    }

    void peerDisconnected(String key, @Nullable String cause) {
        dispatch(listener -> listener.onPeerDisconnected(key, cause));
    }

    void pieceComplete(int piece) {
        dispatch(listener -> listener.onPieceComplete(piece));
    }

    void progress(ProgressSnapshot snapshot) {
        dispatch(listener -> listener.onProgress(snapshot));
    }

    void stateChanged(TaskState from, TaskState to) {
        dispatch(listener -> listener.onStateChanged(from, to));
    }
}
