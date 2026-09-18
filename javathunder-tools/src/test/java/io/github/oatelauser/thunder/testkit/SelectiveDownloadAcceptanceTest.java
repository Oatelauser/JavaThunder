package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.FileFilter;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选择性下载端到端验收：多文件种子（非对齐尺寸——件跨文件边界）只取部分文件，
 * 任务应完成、想要文件字节级一致、结果字节数按必需件计；被过滤文件以稀疏占位
 * 物化（跨界件携带的少量字节会写入，属协议粒度）。
 */
class SelectiveDownloadAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final int PIECE_LENGTH = 16 * 1024;

    @Test
    void downloadsOnlyWantedFilesOfMultiFileTorrent() throws Exception {
        Random random = new Random(88);
        // 拼接流 70000B = 5 件：readme[0,10000) manual[10000,50000) cover[50000,70000)
        // 件 0 跨 readme/manual、件 3 跨 manual/cover——跨界件仍整件下载
        EmbeddedTracker tracker = EmbeddedTracker.start();
        TorrentGenerator.GeneratedMultiFileTorrent seed = TorrentGenerator.generateMultiFile(
                tempDir, "bundle",
                List.of(
                        List.of(List.of("readme.txt"), 10000),
                        List.of(List.of("docs", "manual.pdf"), 40000),
                        List.of(List.of("img", "cover.png"), 20000)),
                PIECE_LENGTH, tracker.announceUrl(), random);
        TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));
        assertEquals(5, meta.pieceCount());

        try (TorrentClient client = TorrentClient.builder()
                .transport(Transports.select())
                .listenPort(23000 + random.nextInt(3000))
                .build()) {
            try (FakeSeeder seeder = FakeSeeder.startMultiFile(meta, seed.rootDir())) {
                seeder.announceTo(tracker);
                DownloadOptions options = DownloadOptions.defaults()
                        .targetDir(tempDir.resolve("dlSel"))
                        .fileFilter(FileFilter.paths("readme.txt", "img/cover.png"));
                DownloadResult result = client.download(seed.torrentFile(), options)
                        .future().get(90, TimeUnit.SECONDS);

                Path out = result.file();
                assertArrayEquals(Files.readAllBytes(seed.rootDir().resolve("readme.txt")),
                        Files.readAllBytes(out.resolve("readme.txt")), "想要文件字节级一致");
                assertArrayEquals(Files.readAllBytes(seed.rootDir().resolve("img").resolve("cover.png")),
                        Files.readAllBytes(out.resolve("img").resolve("cover.png")),
                        "跨界末文件完整（件 3 携带 manual 尾部属预期）");
                // 必需字节 = 件 0、件 3 两个跨界整件 + 件 4 截断（70000 - 4*16384）
                assertEquals(2 * PIECE_LENGTH + (70000 - 4 * PIECE_LENGTH),
                        result.bytes(), "结果字节按必需件计（跨界件整件）");
                assertTrue(Files.exists(out.resolve("docs").resolve("manual.pdf")),
                        "被过滤文件以稀疏占位物化（目录形状完整）");
            }
        }
    }
}
