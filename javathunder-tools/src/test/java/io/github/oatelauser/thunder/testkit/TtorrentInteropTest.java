package io.github.oatelauser.thunder.testkit;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.SharedTorrent;
import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 互操作验收（A1）：以 ttorrent（独立的第三方 BitTorrent 实现）为裁判，双向验证协议兼容。
 * 回环测试只证明我们和自己能对话；这里证明我们和别人的实现能对话。
 * 默认 CI 跳过（@Tag("interop")），显式运行：
 * {@code mvn -pl javathunder-testkit -am test -Dtest=TtorrentInteropTest -Dsurefire.excludedGroups=}
 */
@Tag("interop")
class TtorrentInteropTest {

    @TempDir
    Path dir;

    static boolean refereeAvailable() {
        try {
            Class.forName("com.turn.ttorrent.client.Client");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    void javaThunderDownloadsFromTtorrentSeeder() throws Exception {
        Assumptions.assumeTrue(refereeAvailable(), "ttorrent referee not on classpath");
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "interop.bin", 1_000_000, tracker.announceUrl(), new Random(11));

            Client seeder = startTtorrentSeeder(generated, "seed");
            try {
                try (DefaultTorrentClient client = DefaultTorrentClient.builder()
                        .transportFactory(Transports.fromSystemProperty()).build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")));
                    DownloadResult result = task.future().get(90, TimeUnit.SECONDS);

                    assertEquals(TaskState.COMPLETED, task.state());
                    assertArrayEquals(Files.readAllBytes(generated.contentFile()),
                        Files.readAllBytes(result.file()));
                }
            } finally {
                seeder.stop();
            }
        }
    }

    @Test
    void ttorrentDownloadsFromJavaThunderSeeder() throws Exception {
        Assumptions.assumeTrue(refereeAvailable(), "ttorrent referee not on classpath");
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "interop2.bin", 1_000_000, tracker.announceUrl(), new Random(22));

            // 阶段 A：ttorrent 做种，我们下载并转入做种
            Client seeder = startTtorrentSeeder(generated, "seed-a");
            try (DefaultTorrentClient client = DefaultTorrentClient.builder()
                    .transportFactory(Transports.fromSystemProperty()).build()) {
                DownloadOptions seedOptions = new DownloadOptions(
                    dir.resolve("out"), true, true, true, 0, 0); // resume + verify + seed, 不限速
                DownloadTask task = client.download(generated.torrentFile(), seedOptions);
                DownloadResult result = task.future().get(90, TimeUnit.SECONDS);
                // seedAfterComplete=true 时终态直接是 SEEDING（DownloadSession.complete 先 setState 再完成 future）
                assertEquals(TaskState.SEEDING, task.state());

                // 阶段 B：撤掉 ttorrent 种子源，我们的客户端成为唯一数据源
                seeder.stop();
                Path leechDir = dir.resolve("leech");
                Files.createDirectories(leechDir);
                Client leecher = new Client(InetAddress.getLoopbackAddress(),
                    new SharedTorrent(Files.readAllBytes(generated.torrentFile()), leechDir.toFile()));
                // ttorrent 1.5 的 download()＝share(0)：只启动客户端线程即返回，不阻塞到完成
                leecher.download();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                while (!leecher.isSeed() && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }

                assertTrue(leecher.isSeed(),
                    "ttorrent leecher did not complete within 90s (state=" + leecher.getState() + ")");
                assertArrayEquals(Files.readAllBytes(generated.contentFile()),
                    Files.readAllBytes(leechDir.resolve("interop2.bin")));
                leecher.stop();
                assertEquals(TaskState.SEEDING, task.state());
            } finally {
                seeder.stop();
            }
        }
    }

    @Test
    void javaThunderDownloadsMultiFileFromTtorrentSeeder() throws Exception {
        Assumptions.assumeTrue(refereeAvailable(), "ttorrent referee not on classpath");
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            // 多文件（B3）：件 2 跨 weights.bin/config.json 边界，验证跨界拼装与第三方一致
            TorrentGenerator.GeneratedMultiFileTorrent generated =
                TorrentGenerator.generateMultiFile(dir, "interop-dir", java.util.List.of(
                    java.util.List.of(java.util.List.of("weights.bin"), 600_000),
                    java.util.List.of(java.util.List.of("nested", "config.json"), 130_000),
                    java.util.List.of(java.util.List.of("tokenizer"), 256)),
                    256 * 1024, tracker.announceUrl(), new Random(33));

            // ttorrent 多文件布局：parentDir 下以种子 name 为根；generateMultiFile 已写 dir/interop-dir/
            Client seeder = new Client(InetAddress.getLoopbackAddress(),
                new SharedTorrent(Files.readAllBytes(generated.torrentFile()), dir.toFile()));
            seeder.share();
            try {
                try (DefaultTorrentClient client = DefaultTorrentClient.builder()
                        .transportFactory(Transports.fromSystemProperty()).build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")));
                    task.future().get(90, TimeUnit.SECONDS);

                    assertEquals(TaskState.COMPLETED, task.state());
                    Path outRoot = dir.resolve("out").resolve("interop-dir");
                    assertArrayEquals(Files.readAllBytes(generated.rootDir().resolve("weights.bin")),
                        Files.readAllBytes(outRoot.resolve("weights.bin")));
                    assertArrayEquals(
                        Files.readAllBytes(generated.rootDir().resolve("nested").resolve("config.json")),
                        Files.readAllBytes(outRoot.resolve("nested").resolve("config.json")));
                    assertArrayEquals(Files.readAllBytes(generated.rootDir().resolve("tokenizer")),
                        Files.readAllBytes(outRoot.resolve("tokenizer")));
                }
            } finally {
                seeder.stop();
            }
        }
    }

    private Client startTtorrentSeeder(TorrentGenerator.GeneratedTorrent generated, String subdir)
        throws Exception {
        Path seedDir = dir.resolve(subdir);
        Files.createDirectories(seedDir);
        Files.copy(generated.contentFile(), seedDir.resolve(
            generated.contentFile().getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Client seeder = new Client(InetAddress.getLoopbackAddress(),
            new SharedTorrent(Files.readAllBytes(generated.torrentFile()), seedDir.toFile()));
        seeder.share(); // ttorrent 1.5 反编译确认：share()＝share(-1)，启动后台线程即返回，非阻塞
        return seeder;
    }
}
