package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadOrder;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.FileFilter;
import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 顺序下载端到端验收（DownloadOrder.SEQUENTIAL）：断言**请求首现序**严格递增
 * （在种子方观测——这是顺序语义的真保证；完成事件序受并发校验影响可在相邻件间
 * 局部翻转，不作强断言）。第二个用例验证与选择性下载组合：只在必需件集内按序。
 * 端口说明：listenPort 分段基址只是随机抖动起点，并非跨测试类的防撞约定
 * （窄带彼此重叠、且落在它类的宽带 17000–37000 内），勿据此新增"端口分配表"。
 */
class SequentialDownloadAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final int PIECE_LENGTH = 512 * 1024;

    /** 请求件号的首现序（管线会重复请求同件的不同块，去重保首现）。 */
    private static List<Integer> firstSeenOrder(List<Integer> requested) {
        Set<Integer> seen = new LinkedHashSet<>();
        for (int piece : requested) {
            seen.add(piece);
        }
        return new ArrayList<>(seen);
    }

    @Test
    void sequentialModeRequestsPiecesInIndexOrder() throws Exception {
        Random random = new Random(66);
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent seed = TorrentGenerator.generate(
                    tempDir, "stream.bin", 6 * PIECE_LENGTH, PIECE_LENGTH,
                    tracker.announceUrl(), random);
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));

            try (TorrentClient client = TorrentClient.builder()
                    .transport(Transports.select())
                    .listenPort(24000 + random.nextInt(3000))
                    .build()) {
                try (FakeSeeder seeder = FakeSeeder.start(seed.contentFile(), meta)) {
                    seeder.announceTo(tracker);
                    DownloadOptions options = DownloadOptions.defaults()
                            .targetDir(tempDir.resolve("dlSeq"))
                            .downloadOrder(DownloadOrder.SEQUENTIAL);
                    DownloadTask task = client.download(seed.torrentFile(), options);
                    DownloadResult result = task.future().get(90, TimeUnit.SECONDS);
                    assertArrayEquals(Files.readAllBytes(seed.contentFile()),
                            Files.readAllBytes(result.file()), "内容字节级一致");
                    assertEquals(List.of(0, 1, 2, 3, 4, 5),
                            firstSeenOrder(seeder.requestedPieceOrder()),
                            "请求首现序应按索引严格递增（顺序语义的真保证）");
                }
            }
        }
    }

    @Test
    void sequentialRespectsFileFilterProjection() throws Exception {
        Random random = new Random(67);
        // a=512K(件0) b=1M(件1,2) c=1.5M(件3,4,5)；只要 a 和 c → 必需件 {0,3,4,5} 升序
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            // a=512K(件0) b=1M(件1,2) c=1.5M(件3,4,5)；只要 a 和 c → 必需件 {0,3,4,5} 升序
            TorrentGenerator.GeneratedMultiFileTorrent seed = TorrentGenerator.generateMultiFile(
                    tempDir, "stream-bundle",
                    List.of(
                            List.of(List.of("a.bin"), PIECE_LENGTH),
                            List.of(List.of("b.bin"), 2 * PIECE_LENGTH),
                            List.of(List.of("c.bin"), 3 * PIECE_LENGTH)),
                    PIECE_LENGTH, tracker.announceUrl(), random);
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));

            try (TorrentClient client = TorrentClient.builder()
                    .transport(Transports.select())
                    .listenPort(24400 + random.nextInt(3000))
                    .build()) {
                try (FakeSeeder seeder = FakeSeeder.startMultiFile(meta, seed.rootDir())) {
                    seeder.announceTo(tracker);
                    DownloadOptions options = DownloadOptions.defaults()
                            .targetDir(tempDir.resolve("dlSeqSel"))
                            .downloadOrder(DownloadOrder.SEQUENTIAL)
                            .fileFilter(FileFilter.paths("a.bin", "c.bin"));
                    DownloadTask task = client.download(seed.torrentFile(), options);
                    DownloadResult result = task.future().get(90, TimeUnit.SECONDS);
                    assertArrayEquals(Files.readAllBytes(seed.rootDir().resolve("c.bin")),
                            Files.readAllBytes(result.file().resolve("c.bin")));
                    assertEquals(List.of(0, 3, 4, 5),
                            firstSeenOrder(seeder.requestedPieceOrder()),
                            "只在必需件集内按索引升序请求");
                }
            }
        }
    }
}
