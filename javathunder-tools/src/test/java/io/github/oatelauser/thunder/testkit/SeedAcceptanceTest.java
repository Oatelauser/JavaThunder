package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.SeedOptions;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入已有文件做种验收（G2）：seed() 对 dataDir 下已有数据全量校验后直接 SEEDING。
 * 场景 1 是核心证据链——不启任何 FakeSeeder，seed-only 客户端是唯一数据源，
 * 第二个真实客户端从它完整下载，字节级比对（上传路径端到端工作）。
 */
class SeedAcceptanceTest {

    @TempDir
    Path dir;

    @Test
    void seedsSingleFileFromExistingDataAndServesSecondClient() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "seed-me.bin", 300_000, tracker.announceUrl(), new Random(41));
            // generate 已把数据文件写在 dir/seed-me.bin——即"已有数据"预置形态

            try (DefaultTorrentClient seeder = DefaultTorrentClient.builder()
                    .listenPort(17000 + new Random().nextInt(20000))
                    .transportFactory(Transports.fromSystemProperty()).build()) {
                DownloadTask seedTask = seeder.seed(generated.torrentFile(),
                    SeedOptions.defaults().dataDir(dir));
                assertEquals(TaskState.SEEDING, seedTask.state());
                Thread.sleep(5_000); // 让 announce/loops 稳定，确认不会翻转到 FAILED
                assertEquals(TaskState.SEEDING, seedTask.state());

                // 第二个真实客户端：我们是 swarm 里唯一的种子源，下完即证明上传路径工作
                try (DefaultTorrentClient leecher = DefaultTorrentClient.builder()
                        .listenPort(17000 + new Random().nextInt(20000))
                        .transportFactory(Transports.fromSystemProperty()).build()) {
                    DownloadTask leechTask = leecher.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("leech-out")));
                    DownloadResult result = leechTask.future().get(90, TimeUnit.SECONDS);

                    assertEquals(TaskState.COMPLETED, leechTask.state());
                    assertArrayEquals(Files.readAllBytes(generated.contentFile()),
                        Files.readAllBytes(result.file()));
                }

                // 做种任务持续：future 不完成，且确实上传过数据
                assertEquals(TaskState.SEEDING, seedTask.state());
                assertFalse(seedTask.future().isDone());
                assertTrue(seedTask.snapshot().uploadedBytes() > 0,
                    "seed-only task served no bytes");
            }
        }
    }

    @Test
    void seedsMultiFileTreeFromExistingData() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedMultiFileTorrent generated =
                TorrentGenerator.generateMultiFile(dir, "model-s", List.of(
                    List.of(List.of("weights.bin"), 600),
                    List.of(List.of("nested", "config.json"), 130),
                    List.of(List.of("tokenizer"), 256)),
                    256, tracker.announceUrl(), new Random(43));

            try (DefaultTorrentClient seeder = DefaultTorrentClient.builder()
                    .listenPort(17000 + new Random().nextInt(20000))
                    .transportFactory(Transports.fromSystemProperty()).build()) {
                DownloadTask seedTask = seeder.seed(generated.torrentFile(),
                    SeedOptions.defaults().dataDir(dir));
                assertEquals(TaskState.SEEDING, seedTask.state());

                // 目录树原位保留，无暂存目录
                Path root = generated.rootDir();
                assertTrue(Files.isRegularFile(root.resolve("weights.bin")));
                assertTrue(Files.isRegularFile(root.resolve("nested").resolve("config.json")));
                assertTrue(Files.isRegularFile(root.resolve("tokenizer")));
                assertFalse(Files.exists(dir.resolve("model-s.part")));
            }
        }
    }

    @Test
    void failsSeedingWhenDataCorrupt() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "corrupt.bin", 300_000, tracker.announceUrl(), new Random(47));
            // 破坏第 1 件中部一个字节（默认件长 256KB）
            byte[] data = Files.readAllBytes(generated.contentFile());
            data[TorrentGenerator.DEFAULT_PIECE_LENGTH + 1_000] ^= 0x55;
            Files.write(generated.contentFile(), data);

            try (DefaultTorrentClient seeder = DefaultTorrentClient.builder()
                    .listenPort(17000 + new Random().nextInt(20000))
                    .transportFactory(Transports.fromSystemProperty()).build()) {
                DownloadTask seedTask = seeder.seed(generated.torrentFile(),
                    SeedOptions.defaults().dataDir(dir));

                assertEquals(TaskState.FAILED, seedTask.state());
                ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> seedTask.future().get(1, TimeUnit.SECONDS));
                assertTrue(exception.getCause().getMessage().contains("use download()"),
                    "failure should point callers at download(), got: "
                        + exception.getCause().getMessage());
            }
        }
    }

    @Test
    void pauseAndResumeReturnSeedTaskToSeeding() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "pause-seed.bin", 300_000, tracker.announceUrl(), new Random(53));

            try (DefaultTorrentClient seeder = DefaultTorrentClient.builder()
                    .listenPort(17000 + new Random().nextInt(20000))
                    .transportFactory(Transports.fromSystemProperty()).build()) {
                DownloadTask seedTask = seeder.seed(generated.torrentFile(),
                    SeedOptions.defaults().dataDir(dir));
                assertEquals(TaskState.SEEDING, seedTask.state());

                seedTask.pause();
                assertEquals(TaskState.PAUSED, seedTask.state());

                seedTask.resume();
                assertEquals(TaskState.SEEDING, seedTask.state(),
                    "resume from paused SEEDING must return to SEEDING, not DOWNLOADING");
                Thread.sleep(1_000); // 稳定性：不因后台 loop 翻转
                assertEquals(TaskState.SEEDING, seedTask.state());
            }
        }
    }

    /**
     * 存储识别边界（行为钉住）：download() 不采用已有同名等长文件——即使内容全错，
     * 仍在 .part 全量重下后覆盖最终名，终态内容正确且不留 .part
     * （G2 的"导入已有数据"只对 seed() 生效）。
     */
    @Test
    void downloadOverwritesSameLengthWrongContentFinalFile() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "dl-exist.bin", 300_000, tracker.announceUrl(), new Random(59));
            byte[] pristine = Files.readAllBytes(generated.contentFile());
            Path pristineCopy = dir.resolve("pristine.bin");
            Files.write(pristineCopy, pristine);

            byte[] wrong = new byte[300_000];
            new Random(61).nextBytes(wrong);
            Files.write(dir.resolve("dl-exist.bin"), wrong); // 同名等长、内容全错

            TorrentMetadata meta =
                TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));
            try (FakeSeeder seeder = FakeSeeder.start(pristineCopy, meta)) {
                seeder.announceTo(tracker);
                try (DefaultTorrentClient client = DefaultTorrentClient.builder()
                        .listenPort(17000 + new Random().nextInt(20000))
                        .transportFactory(Transports.fromSystemProperty()).build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir));
                    DownloadResult result = task.future().get(90, TimeUnit.SECONDS);

                    assertEquals(TaskState.COMPLETED, task.state());
                    assertArrayEquals(pristine, Files.readAllBytes(result.file()));
                    assertFalse(Files.exists(dir.resolve("dl-exist.bin.part")));
                }
            }
        }
    }
}
