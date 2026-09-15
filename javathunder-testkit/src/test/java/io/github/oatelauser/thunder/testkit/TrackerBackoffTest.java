package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tracker 指数退避（DESIGN §3.4）：全部 tracker 失败时 announce 间隔按 interval×2^k 递增。
 * 关停内嵌 tracker 使端口拒连（loopback 上立即 ECONNREFUSED），观察 12.5s 窗口内的
 * 失败 announce 时间戳：默认间隔 5s，首次全失败后应翻倍为 10s——
 * 有退避 ⇒ 事件落在 t≈0/10（2 次，间隔 ~10s）；无退避 ⇒ t≈0/5/10（3 次）。
 * 监听器注册与 STARTED announce 存在竞态，首个事件可能丢失（⇒ 仅 1 次），同样符合退避。
 */
class TrackerBackoffTest {

    private static final long WINDOW_MILLIS = 12_500;

    @TempDir
    Path dir;

    @Test
    void allTrackersFailingDoublesAnnounceInterval() throws Exception {
        String deadUrl;
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            deadUrl = tracker.announceUrl();
        } // close 后端口空置：对该端口的 announce 立刻连接被拒

        TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
            dir, "backoff.bin", 256 * 1024, deadUrl, new Random(5));

        try (DefaultTorrentClient client = DefaultTorrentClient.builder()
                .transportFactory(Transports.fromSystemProperty())
                .listenPort(17000 + new Random().nextInt(20000)).build()) {
            List<Long> failureNanos = Collections.synchronizedList(new ArrayList<>());
            DownloadTask task = client.download(generated.torrentFile(),
                DownloadOptions.defaults().targetDir(dir.resolve("out")));
            task.addListener(new TaskListener() {
                @Override
                public void onTrackerAnnounce(String trackerUrl, String failureReason,
                                              int seeders, int leechers) {
                    if (failureReason != null) {
                        failureNanos.add(System.nanoTime());
                    }
                }
            });
            try {
                Thread.sleep(WINDOW_MILLIS);
                List<Long> times;
                synchronized (failureNanos) {
                    times = new ArrayList<>(failureNanos);
                }
                // 无退避的恒定 5s 间隔在窗口内会打出 3 次；退避后至多 2 次（首事件竞态丢失则 1 次）
                assertTrue(times.size() >= 1 && times.size() <= 2,
                    () -> "expected at most 2 failed announces with backoff, got " + times.size());
                if (times.size() == 2) {
                    double gapSeconds = (times.get(1) - times.get(0)) / 1e9;
                    // 5s → 10s 翻倍（理论 10s，容忍调度噪声）；无退避时恒为 ~5s
                    assertTrue(gapSeconds >= 8.0 && gapSeconds <= 14.0,
                        () -> String.format("announce gap %.2fs, expected ~10s (doubled backoff)", gapSeconds));
                }
            } finally {
                task.cancel(true);
            }
        }
    }
}
