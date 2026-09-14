package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
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

/** 多 Peer 并发：4 个 seeder 同时供种，验证调度跨 Peer 分片正确且吞吐聚合。 */
class MultiPeerAcceptanceTest {

    @TempDir
    Path dir;

    @Test
    void downloadsFromFourConcurrentSeeders() throws Exception {
        // 尺寸可参数化以测聚合吞吐：-DmultiPeer.mb=64（默认小尺寸跑正确性）
        int sizeBytes = Integer.getInteger("multiPeer.mb", 1) == 1
            ? 1_500_000 : Integer.getInteger("multiPeer.mb", 1) * 1024 * 1024;
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "multi.bin", sizeBytes, tracker.announceUrl(), new Random(99));
            TorrentMetadata meta =
                TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));

            try (EmbeddedTracker t = tracker;
                 FakeSeeder s1 = FakeSeeder.start(generated.contentFile(), meta);
                 FakeSeeder s2 = FakeSeeder.start(generated.contentFile(), meta);
                 FakeSeeder s3 = FakeSeeder.start(generated.contentFile(), meta);
                 FakeSeeder s4 = FakeSeeder.start(generated.contentFile(), meta)) {
                s1.announceTo(t);
                s2.announceTo(t);
                s3.announceTo(t);
                s4.announceTo(t);

                try (DefaultTorrentClient client = DefaultTorrentClient.builder()
                        .transportFactory(Transports.fromSystemProperty())
                        .maxPeersPerTask(50).build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")));
                    DownloadResult result = task.future().get(60, TimeUnit.SECONDS);

                    assertEquals(TaskState.COMPLETED, task.state());
                    assertArrayEquals(Files.readAllBytes(generated.contentFile()),
                        Files.readAllBytes(result.file()));
                    System.out.printf("MULTI-PEER 4-seeders: %d bytes in %.2fs = %.0f MB/s aggregate%n",
                        result.bytes(), result.elapsed().toMillis() / 1000.0,
                        result.bytes() / 1048576.0 / (result.elapsed().toMillis() / 1000.0));
                }
            }
        }
    }
}
