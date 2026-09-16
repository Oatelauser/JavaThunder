package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 多文件验收（B3）：模拟模型目录（权重+嵌套配置+空文件），Piece 跨文件边界。 */
class MultiFileAcceptanceTest {

    @TempDir
    Path dir;

    @Test
    void downloadsModelDirectoryEndToEnd() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            // 模型目录：model-x/weights.bin (600B) + model-x/nested/config.json (130B)
            // + model-x/tokenizer (256B)。件长 256 → 件 2 跨 weights.bin 与 config.json 边界
            TorrentGenerator.GeneratedMultiFileTorrent generated =
                TorrentGenerator.generateMultiFile(dir, "model-x", List.of(
                    List.of(List.of("weights.bin"), 600),
                    List.of(List.of("nested", "config.json"), 130),
                    List.of(List.of("tokenizer"), 256)),
                    256, tracker.announceUrl(), new Random(31));
            TorrentMetadata meta =
                TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));
            assertEquals(986, meta.length());
            assertEquals(4, meta.pieceCount());

            try (FakeSeeder seeder = FakeSeeder.startMultiFile(meta, generated.rootDir())) {
                seeder.announceTo(tracker);
                try (TorrentClient client = TorrentClient.builder()
                        .transport(Transports.select()).build()) {

                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")));
                    DownloadResult result = task.future().get(60, TimeUnit.SECONDS);

                    assertEquals(TaskState.COMPLETED, task.state());
                    Path outRoot = dir.resolve("out").resolve("model-x");
                    assertTrue(Files.exists(outRoot.resolve("weights.bin")));
                    assertTrue(Files.exists(outRoot.resolve("nested").resolve("config.json")));
                    assertTrue(Files.exists(outRoot.resolve("tokenizer")));
                    assertFalse(Files.exists(dir.resolve("out").resolve("model-x.part")));

                    assertArrayEquals(
                        Files.readAllBytes(generated.rootDir().resolve("weights.bin")),
                        Files.readAllBytes(outRoot.resolve("weights.bin")));
                    assertArrayEquals(
                        Files.readAllBytes(generated.rootDir().resolve("nested").resolve("config.json")),
                        Files.readAllBytes(outRoot.resolve("nested").resolve("config.json")));
                    assertArrayEquals(
                        Files.readAllBytes(generated.rootDir().resolve("tokenizer")),
                        Files.readAllBytes(outRoot.resolve("tokenizer")));
                }
            }
        }
    }
}
