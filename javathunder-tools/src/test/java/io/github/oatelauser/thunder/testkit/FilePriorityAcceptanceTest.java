package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.FilePriority;
import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 文件优先级端到端验收：高优先级文件的件在常规文件之前被请求（观测点 =
 * FakeSeeder 的请求首现序——与顺序下载验收同一确定性手段）。布局特意非对齐，
 * 覆盖跨界件取最高优先级的投影语义。
 */
class FilePriorityAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final int PIECE_LENGTH = 16 * 1024;

    @Test
    void highPriorityFileIsRequestedFirst() throws Exception {
        Random random = new Random(99);
        // readme[0,10000) manual[10000,50000) cover[50000,70000) = 5 件（件 0/3 跨界）
        // cover 设 HIGH → 件 3（压 manual 尾 + cover 头）与件 4（纯 cover）优先
        EmbeddedTracker tracker = EmbeddedTracker.start();
        TorrentGenerator.GeneratedMultiFileTorrent seed = TorrentGenerator.generateMultiFile(
                tempDir, "prio-bundle",
                List.of(
                        List.of(List.of("readme.txt"), 10000),
                        List.of(List.of("docs", "manual.pdf"), 40000),
                        List.of(List.of("cover.png"), 20000)),
                PIECE_LENGTH, tracker.announceUrl(), random);
        TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));

        try (TorrentClient client = TorrentClient.builder()
                .transport(Transports.select())
                .listenPort(25000 + random.nextInt(3000))
                .build()) {
            try (FakeSeeder seeder = FakeSeeder.startMultiFile(meta, seed.rootDir())) {
                seeder.announceTo(tracker);
                DownloadOptions options = DownloadOptions.defaults()
                        .targetDir(tempDir.resolve("dlPrio"))
                        .filePriorities(path -> path.get(path.size() - 1).equals("cover.png")
                                ? FilePriority.HIGH : FilePriority.NORMAL);
                DownloadResult result = client.download(seed.torrentFile(), options)
                        .future().get(90, TimeUnit.SECONDS);

                assertArrayEquals(Files.readAllBytes(seed.rootDir().resolve("cover.png")),
                        Files.readAllBytes(result.file().resolve("cover.png")));
                Set<Integer> firstSeen = new LinkedHashSet<>(seeder.requestedPieceOrder());
                assertEquals(List.of(3, 4, 0, 1, 2), List.copyOf(firstSeen),
                        "高优先级件（3/4）应先于常规件（0/1/2）被请求");
            }
        }
    }
}
