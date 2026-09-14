package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import io.github.oatelauser.thunder.core.internal.storage.ResumeState;
import io.github.oatelauser.thunder.core.internal.storage.StorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回环验收（DESIGN §1.3 第 1 条 + 断点续传）：生成种子 → 假 seeder → 完整下载 → 字节比对。 */
class LoopbackAcceptanceTest {

    @TempDir
    Path dir;

    private final Random random = new Random();

    @Test
    void downloadsGeneratedTorrentEndToEnd() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "payload.bin", 1_500_000, tracker.announceUrl(), new Random(42));
            TorrentMetadata meta =
                TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));

            try (FakeSeeder seeder = FakeSeeder.start(generated.contentFile(), meta)) {
                seeder.announceTo(tracker);
                try (DefaultTorrentClient client = DefaultTorrentClient.builder()
                        .listenPort(17000 + random.nextInt(20000)).build()) {

                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")));
                    DownloadResult result = task.future().get(60, TimeUnit.SECONDS);

                    assertEquals(TaskState.COMPLETED, task.state());
                    assertEquals(meta.length(), result.bytes());
                    assertArrayEquals(Files.readAllBytes(generated.contentFile()),
                        Files.readAllBytes(result.file()));
                    assertFalse(Files.exists(dir.resolve("out/payload.bin.part")));
                    assertFalse(Files.exists(dir.resolve("out/payload.bin.jt-resume")));
                    assertTrue(result.file().endsWith("payload.bin"));
                }
            }
        }
    }

    @Test
    void resumesFromPartialState() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "resume.bin", 4 * 256 * 1024, tracker.announceUrl(), new Random(7));
            TorrentMetadata meta =
                TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));
            Path out = dir.resolve("resume-out");
            byte[] content = Files.readAllBytes(generated.contentFile());

            // 预写 piece 0/1 并保存 resume 状态，模拟上次中断
            try (StorageManager storage = new StorageManager(meta, out)) {
                storage.writeBlock(0, 0, Arrays.copyOfRange(content, 0, 256 * 1024));
                storage.verifyPiece(0);
                storage.writeBlock(1, 0, Arrays.copyOfRange(content, 256 * 1024, 512 * 1024));
                storage.verifyPiece(1);
                Bitfield bitfield = new Bitfield(4);
                bitfield.set(0);
                bitfield.set(1);
                ResumeState.save(out.resolve("resume.bin.jt-resume"),
                    new ResumeState(meta.infoHash(), 4, bitfield, 0, 512 * 1024,
                        System.currentTimeMillis()));
            }

            try (FakeSeeder seeder = FakeSeeder.start(generated.contentFile(), meta)) {
                seeder.announceTo(tracker);
                try (DefaultTorrentClient client = DefaultTorrentClient.builder()
                        .listenPort(17000 + random.nextInt(20000)).build()) {

                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(out));
                    DownloadResult result = task.future().get(60, TimeUnit.SECONDS);

                    assertEquals(TaskState.COMPLETED, task.state());
                    assertArrayEquals(content, Files.readAllBytes(result.file()));
                }
            }
        }
    }
}
