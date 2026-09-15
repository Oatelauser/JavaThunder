package io.github.oatelauser.thunder.core.internal;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快速入门冒烟（对应 docs/MANUAL.md §2.1）：
 * 公网 Ubuntu 种子，验证"任务启动 + 进度事件流"即可——完整下载 6GB 需数小时，
 * 不作为断言条件。断点数据保留在 targetDir，重跑会继续累计。
 * 种子路径是本机路径：文件不存在时本测试自动跳过（CI/其他环境）。
 *
 * @author <a href="mailto:yangsheng1993812@gmail.com">Oatelauser</a>
 * @since 0.2.0
 */
class QuickStartTest {

    @Test
    @Timeout(60) // 引擎异常时的兜底，正常路径 ~3s 内收到首帧
    void downloadFromTorrentEmitsProgress() throws Exception {
        Path torrent = Path.of("D:\\下载软件包\\ubuntu-24.04.4-desktop-amd64.iso.torrent");
        Assumptions.assumeTrue(Files.exists(torrent),
            "本机种子文件不存在，跳过（该测试为作者环境冒烟）");
        CountDownLatch firstFrame = new CountDownLatch(1);
        CountDownLatch fifthFrame = new CountDownLatch(1);
        long[] frames = {0};

        try (DefaultTorrentClient client = DefaultTorrentClient.builder().build()) {
            DownloadTask task = client.download(
                torrent,
                DownloadOptions.defaults().targetDir(Path.of("D:\\下载软件包\\ubuntu")));

            task.addListener(new TaskListener() {
                @Override
                public void onProgress(ProgressSnapshot p) {
                    // 注意用 %n 换行：\r 不触发流刷新，IDE/测试控制台会一行都看不到
                    System.out.printf("progress %.2f%%  ↓%dKB/s  peers=%d  eta=%ss%n",
                        p.fraction() * 100, p.downloadRateBps() / 1024,
                        p.connectedPeers(), p.etaMillis() == null ? "-" : p.etaMillis() / 1000);
                    frames[0]++;
                    firstFrame.countDown();
                    if (frames[0] >= 5) {
                        fifthFrame.countDown();
                    }
                }
            });

            // 集成成功的判据：进度事件流到达（无论当前网络能连上几个 Peer）
            assertTrue(firstFrame.await(15, TimeUnit.SECONDS), "15s 内应收到首帧进度事件");
            assertTrue(fifthFrame.await(30, TimeUnit.SECONDS), "30s 内应持续收到进度事件");

            System.out.printf("smoke ok: state=%s, snapshot fraction=%.2f%%%n",
                task.state(), task.snapshot().fraction() * 100);
            task.cancel(false); // 保留已下数据（含断点），下完整 ISO 可在任意时候重跑继续
        }
    }
}
