package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.TaskListener;
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

/**
 * 离线快速入门（对应 docs/MANUAL.md §2.1）：零网络跑通"下载→校验→完成"全链路。
 * 在 IDEA 里直接运行本测试即可看到进度行与完成输出。
 */
class OfflineQuickStartTest {

    @TempDir
    Path dir;

    @Test
    void offlineSwarmDownloadCompletes() throws Exception {
        // ① 内嵌 tracker（"电话簿"，仅交换节点地址，不碰数据）
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            // ② 造种子：1MB 内容 + 配套 .torrent
            TorrentGenerator.GeneratedTorrent gen = TorrentGenerator.generate(
                dir, "hello.bin", 1_000_000, tracker.announceUrl(), new Random(42));
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(gen.torrentFile()));

            // ③ 种子源：已知良好的假做种者，向 tracker 注册
            try (FakeSeeder seeder = FakeSeeder.start(gen.contentFile(), meta)) {
                seeder.announceTo(tracker);

                // ④ 下载方：就是你要写的业务代码
                try (DefaultTorrentClient client = DefaultTorrentClient.builder().build()) {
                    DownloadTask task = client.download(gen.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")));

                    task.addListener(new TaskListener() {
                        @Override public void onProgress(io.github.oatelauser.thunder.api.ProgressSnapshot p) {
                            System.out.printf("progress %.1f%%  ↓%dKB/s  peers=%d%n",
                                p.fraction() * 100, p.downloadRateBps() / 1024, p.connectedPeers());
                        }
                    });

                    DownloadResult result = task.future().get(30, TimeUnit.SECONDS); // 完成即全片校验通过
                    System.out.printf("完成: %s (%d 字节)%n", result.file(), result.bytes());

                    assertEquals(1_000_000, result.bytes());
                    assertArrayEquals(Files.readAllBytes(gen.contentFile()),
                        Files.readAllBytes(result.file())); // 字节级一致
                }
            }
        }
    }
}
