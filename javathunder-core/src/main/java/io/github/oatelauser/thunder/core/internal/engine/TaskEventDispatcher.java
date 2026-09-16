package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

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

    void error(Throwable error) {
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onError(error);
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    void trackerAnnounce(String url, @Nullable String failure, int seeders, int leechers) {
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onTrackerAnnounce(url, failure, seeders, leechers);
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    void peerConnected(String key) {
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onPeerConnected(key);
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    void peerDisconnected(String key, @Nullable String cause) {
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onPeerDisconnected(key, cause);
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    void pieceComplete(int piece) {
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onPieceComplete(piece);
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    void progress(ProgressSnapshot snapshot) {
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onProgress(snapshot);
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    void stateChanged(TaskState from, TaskState to) {
        for (TaskListener listener : listeners) {
            eventExecutor.execute(() -> {
                try {
                    listener.onStateChanged(from, to);
                } catch (RuntimeException ignored) {
                }
            });
        }
    }
}
