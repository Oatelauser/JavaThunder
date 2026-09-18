package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadOrder;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.FileFilter;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 顺序下载端到端验收（DownloadOrder.SEQUENTIAL）：完成事件按 Piece 索引严格递增
 * （流式消费——首文件最先凑齐）。件取 512KiB（32 块 = 管线深度），单件在途、
 * 顺序确定；第二个用例验证与选择性下载组合：只在必需件集内按序。
 */
class SequentialDownloadAcceptanceTest {

    @TempDir
    Path tempDir;

    /** 512KiB：32 块恰为管线深度，任意时刻单件在途，完成序 = 请求序。 */
    private static final int PIECE_LENGTH = 512 * 1024;

    @Test
    void sequentialModeCompletesPiecesInIndexOrder() throws Exception {
        Random random = new Random(66);
        EmbeddedTracker tracker = EmbeddedTracker.start();
        TorrentGenerator.GeneratedTorrent seed = TorrentGenerator.generate(
                tempDir, "stream.bin", 6 * PIECE_LENGTH, PIECE_LENGTH,
                tracker.announceUrl(), random);
        TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));
        List<Integer> completed = new CopyOnWriteArrayList<>();

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
                task.addListener(new TaskListener() {
                    @Override
                    public void onPieceComplete(int pieceIndex) {
                        completed.add(pieceIndex);
                    }
                });
                DownloadResult result = task.future().get(90, TimeUnit.SECONDS);
                assertArrayEquals(Files.readAllBytes(seed.contentFile()),
                        Files.readAllBytes(result.file()), "内容字节级一致");
            }
        }
        // 严格递增即顺序语义（0..5）——顺序模式是构造性保证，不靠稀缺度巧合
        assertEquals(List.of(0, 1, 2, 3, 4, 5), completed, "完成事件应按索引严格递增");
    }

    @Test
    void sequentialRespectsFileFilterProjection() throws Exception {
        Random random = new Random(67);
        // a=512K(件0) b=1M(件1,2) c=1.5M(件3,4,5)；只要 a 和 c → 必需件 {0,3,4,5} 升序
        EmbeddedTracker tracker = EmbeddedTracker.start();
        TorrentGenerator.GeneratedMultiFileTorrent seed = TorrentGenerator.generateMultiFile(
                tempDir, "stream-bundle",
                List.of(
                        List.of(List.of("a.bin"), PIECE_LENGTH),
                        List.of(List.of("b.bin"), 2 * PIECE_LENGTH),
                        List.of(List.of("c.bin"), 3 * PIECE_LENGTH)),
                PIECE_LENGTH, tracker.announceUrl(), random);
        TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));
        List<Integer> completed = new CopyOnWriteArrayList<>();

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
                task.addListener(new TaskListener() {
                    @Override
                    public void onPieceComplete(int pieceIndex) {
                        completed.add(pieceIndex);
                    }
                });
                DownloadResult result = task.future().get(90, TimeUnit.SECONDS);
                assertArrayEquals(Files.readAllBytes(seed.rootDir().resolve("c.bin")),
                        Files.readAllBytes(result.file().resolve("c.bin")));
            }
        }
        assertEquals(List.of(0, 3, 4, 5), completed, "只在必需件集内按索引升序完成");
    }
}
