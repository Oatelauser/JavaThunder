package com.example.thunder.web;

import com.example.thunder.service.DownloadService;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 接入姿势三：进度暴露模式（完整说明见 docs/MANUAL.md §6.6）。
 *
 * <ul>
 *   <li>轮询：GET /{id} 直接读 task.snapshot()——无回调、无状态，最简。</li>
 *   <li>推送：GET /{id}/events 把 TaskListener 的 onProgress/onStateChanged 转发为 SSE 帧；
 *       回调发生在库的专用事件线程池上（ThunderConfiguration 注入），SseEmitter
 *       允许任意线程发送，但要注意超时与完成处理。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/downloads")
public class DownloadController {

    /** POST 请求体；targetDir 缺省为 "downloads"。 */
    public record CreateDownloadRequest(String source, String targetDir) {
    }

    /** POST 202 响应体。 */
    public record DownloadCreatedView(UUID id) {
    }

    /** GET 进度响应体：state 来自任务状态机，其余字段一对一来自 ProgressSnapshot。 */
    public record DownloadProgressView(UUID id, String state, double fraction,
            long downloadedBytes, long uploadedBytes, long downloadRateBps, long uploadRateBps,
            int connectedPeers, double availability, Long etaMillis) {

        static DownloadProgressView of(UUID id, TaskState state, ProgressSnapshot snapshot) {
            return new DownloadProgressView(id, state.name(), snapshot.fraction(),
                    snapshot.downloadedBytes(), snapshot.uploadedBytes(),
                    snapshot.downloadRateBps(), snapshot.uploadRateBps(),
                    snapshot.connectedPeers(), snapshot.availability(), snapshot.etaMillis());
        }
    }

    /** SSE 的 state 帧负载。 */
    record StateChangeView(String from, String to) {
    }

    private final DownloadService downloads;

    public DownloadController(DownloadService downloads) {
        this.downloads = downloads;
    }

    /** 提交下载（.torrent 路径或 magnet: URI），立即返回 202 + 任务 ID，下载在后台进行。 */
    @PostMapping
    public ResponseEntity<DownloadCreatedView> create(@RequestBody CreateDownloadRequest request)
            throws Exception {
        if (request.source() == null || request.source().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Path targetDir = request.targetDir() == null || request.targetDir().isBlank()
                ? Path.of("downloads")
                : Path.of(request.targetDir());
        UUID id = downloads.submit(request.source(), targetDir);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new DownloadCreatedView(id));
    }

    /** 轮询进度：直接拉快照，字段见 ProgressSnapshot。 */
    @GetMapping("/{id}")
    public DownloadProgressView progress(@PathVariable UUID id) {
        DownloadTask task = downloads.require(id);
        return DownloadProgressView.of(id, task.state(), task.snapshot());
    }

    /**
     * SSE 进度流：连上先发一帧 snapshot（立即有数据），随后转发库事件——
     * progress 帧（~500ms 一拍）与 state 帧；终态（COMPLETED/CANCELLED/FAILED）后流结束。
     */
    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id) {
        DownloadTask task = downloads.require(id);
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 超时兜底；正常路径由终态帧结束

        AtomicBoolean open = new AtomicBoolean(true);
        /** API 没有 removeListener：关闭后（终态/超时/客户端断开）回调成空操作即可（任务生命周期有限）。 */
        Runnable closeOnce = () -> {
            if (open.compareAndSet(true, false)) {
                emitter.complete();
            }
        };
        Object sendLock = new Object(); // 回调可能来自事件池的多个线程，串行化发送保证帧有序
        BiConsumer<String, Object> send = (event, data) -> {
            if (!open.get()) {
                return;
            }
            synchronized (sendLock) {
                if (!open.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException clientGone) {
                    closeOnce.run();
                }
            }
        };

        task.addListener(new TaskListener() {
            @Override
            public void onProgress(ProgressSnapshot snapshot) {
                send.accept("progress", DownloadProgressView.of(id, task.state(), snapshot));
            }

            @Override
            public void onStateChanged(TaskState from, TaskState to) {
                send.accept("state", new StateChangeView(from.name(), to.name()));
                if (to == TaskState.COMPLETED || to == TaskState.CANCELLED || to == TaskState.FAILED) {
                    closeOnce.run();
                }
            }

            @Override
            public void onError(Throwable error) {
                send.accept("error", Map.of("message", String.valueOf(error)));
            }
        });

        // 初始帧：连接即返回当前快照，前端不用等第一个事件拍
        send.accept("snapshot", DownloadProgressView.of(id, task.state(), task.snapshot()));
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> {
            open.set(false);
            emitter.complete();
        });
        return emitter;
    }

    /** 取消下载。deleteData=true 连同本地数据与断点一并删除；已完成的任务是空操作。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean deleteData) {
        return downloads.cancel(id, deleteData)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** 下载源非法（文件不存在 / 磁力格式错误等）→ 400。 */
    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    public ResponseEntity<Map<String, String>> badSource(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(exception.getMessage())));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }
}
