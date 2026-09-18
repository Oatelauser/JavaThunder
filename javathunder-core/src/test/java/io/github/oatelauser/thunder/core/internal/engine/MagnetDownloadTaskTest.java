package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.MagnetUri;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 磁力任务元数据阶段的终态语义：用户取消必须保持 CancellationException 终态
 * （调用方据此区分"取消"与"失败"，不得 wrap 成 IllegalStateException）；
 * 真实失败照常归一为 IllegalStateException。
 */
class MagnetDownloadTaskTest {

    @Test
    void cancelDuringMetadataPhaseStaysCancelled() {
        CompletableFuture<TorrentMetadata> metadata = new CompletableFuture<>();
        AtomicInteger releases = new AtomicInteger();
        MagnetDownloadTask task = newTask(metadata, releases);

        task.cancel(false);

        assertTrue(task.future().isCancelled());
        assertThrows(CancellationException.class, () -> task.future().get());
        assertEquals(1, releases.get(), "磁力槽位恰好释放一次（cancel 路径）");
    }

    @Test
    void metadataFailureCompletesAsIllegalState() {
        CompletableFuture<TorrentMetadata> metadata = new CompletableFuture<>();
        AtomicInteger releases = new AtomicInteger();
        MagnetDownloadTask task = newTask(metadata, releases);

        metadata.completeExceptionally(new IllegalStateException("peer gone"));

        ExecutionException e = assertThrows(ExecutionException.class, () -> task.future().get());
        IllegalStateException cause = assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(cause.getMessage().contains("peer gone"));
        assertEquals(1, releases.get());
    }

    @Test
    void chainedUpstreamCancellationAlsoStaysCancelled() {
        // 生产接线是链式 future（fetch().thenApply().thenCompose()）：上游被 cancel 时
        // 到达 whenComplete 的取消可能包在 CompletionException 里传播，同样不得当失败
        CompletableFuture<byte[]> upstream = new CompletableFuture<>();
        CompletableFuture<TorrentMetadata> metadata = upstream.thenApply(bytes -> {
            throw new AssertionError("upstream cancelled: mapper must not run");
        });
        MagnetDownloadTask task = newTask(metadata, new AtomicInteger());

        upstream.cancel(true);

        assertThrows(CancellationException.class, () -> task.future().get());
    }

    private static MagnetDownloadTask newTask(CompletableFuture<TorrentMetadata> metadata,
            AtomicInteger releases) {
        return new MagnetDownloadTask(metadata, magnet(), DownloadOptions.defaults(),
                (meta, options) -> {
                    throw new AssertionError("starter must not run in metadata-phase tests");
                },
                releases::incrementAndGet, Runnable::run);
    }

    private static MagnetUri magnet() {
        return new MagnetUri(new byte[20], "magnet-test", List.of());
    }
}
