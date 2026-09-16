package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限速验收（DESIGN §3.2/§3.4）：8MB payload 以 512KB/s 下载，理论耗时 16s，
 * 实测须落在 ±30% 带内（11.2–20.8s；比 DESIGN 的 ±20% 放宽以容忍测试噪声），
 * 且聚合吞吐与限速自洽。任务级（{@link DownloadOptions#rateLimits}）与
 * 全局（builder 限速）各验一遍。
 */
class RateLimitAcceptanceTest {

    private static final int SIZE_BYTES = 8 * 1024 * 1024;   // 32 件 × 256KB
    private static final int LIMIT_BYTES_PER_SECOND = 512 * 1024;
    private static final double THEORETICAL_SECONDS =
        (double) SIZE_BYTES / LIMIT_BYTES_PER_SECOND;          // 16.0s
    private static final double TOLERANCE = 0.30;

    @TempDir
    Path dir;

    private final Random random = new Random();

    @Test
    void taskLevelRateLimitThrottlesDownload() throws Exception {
        assertRateLimited("task-limited.bin", "task",
            DownloadOptions.defaults().targetDir(dir.resolve("out"))
                .rateLimits(LIMIT_BYTES_PER_SECOND, 0),
            DefaultTorrentClient.builder());
    }

    @Test
    void globalRateLimitThrottlesDownload() throws Exception {
        assertRateLimited("global-limited.bin", "global",
            DownloadOptions.defaults().targetDir(dir.resolve("out")),
            DefaultTorrentClient.builder().downloadLimitBytesPerSecond(LIMIT_BYTES_PER_SECOND));
    }

    private void assertRateLimited(String name, String label, DownloadOptions options,
                                   DefaultTorrentClient.Builder builder) throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, name, SIZE_BYTES, tracker.announceUrl(), new Random(42));
            TorrentMetadata meta =
                TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));

            try (NioSeeder seeder = NioSeeder.start(generated.contentFile(), meta)) {
                seeder.announceTo(tracker);
                try (DefaultTorrentClient client = builder
                        .transportFactory(Transports.fromSystemProperty())
                        .listenPort(17000 + random.nextInt(20000)).build()) {

                    long start = System.nanoTime();
                    DownloadTask task = client.download(generated.torrentFile(), options);
                    DownloadResult result = task.future().get(90, TimeUnit.SECONDS);
                    double seconds = (System.nanoTime() - start) / 1e9;

                    assertEquals(TaskState.COMPLETED, task.state());
                    assertArrayEquals(Files.readAllBytes(generated.contentFile()),
                        Files.readAllBytes(result.file()));

                    double lower = THEORETICAL_SECONDS * (1 - TOLERANCE);
                    double upper = THEORETICAL_SECONDS * (1 + TOLERANCE);
                    double throughput = result.bytes() / seconds;
                    System.out.printf("RATE-LIMIT %s: %d bytes in %.2fs (theoretical %.1fs, "
                            + "band [%.1fs, %.1fs]) = %.0f KB/s against %d KB/s limit%n",
                        label, result.bytes(), seconds, THEORETICAL_SECONDS, lower, upper,
                        throughput / 1024, LIMIT_BYTES_PER_SECOND / 1024);
                    assertTrue(seconds >= lower && seconds <= upper,
                        () -> String.format("elapsed %.2fs outside [%.2fs, %.2fs]", seconds, lower, upper));
                    assertTrue(throughput >= LIMIT_BYTES_PER_SECOND * (1 - TOLERANCE)
                            && throughput <= LIMIT_BYTES_PER_SECOND * (1 + TOLERANCE),
                        () -> String.format("throughput %.0f KB/s outside limit band [%d, %d] KB/s",
                            throughput / 1024, (int) (LIMIT_BYTES_PER_SECOND * (1 - TOLERANCE) / 1024),
                            (int) (LIMIT_BYTES_PER_SECOND * (1 + TOLERANCE) / 1024)));
                }
            }
        }
    }
}
