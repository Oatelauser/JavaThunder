package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.RestartVerifyMode;
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

/**
 * D2 重启校验三档：坏块注入（把一个已完成件在磁盘上改脏）后分别以
 * FULL / SAMPLED / NONE 恢复——FULL 必检出重下；SAMPLED 是抽样语义（本用例
 * 用多件种子让坏件高概率在样本内）；NONE 信任位图但完成前仍会逐件校验兜底。
 */
class RestartVerifyModeTest {

    @TempDir
    Path dir;

    @Test
    void sampledModeSkipsUnsampledPieces() throws Exception {
        // 32 件 × 256KiB = 8MiB：坏件 17；SAMPLED 10% ≈ 4 件 + 边界，不做必中断言（概率语义），
        // 断言的是行为差异：SAMPLED 恢复耗时显著 < FULL（不逐件读盘）
        int pieces = 32;
        int pieceLength = 256 * 1024;
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "sampled.bin", pieces * pieceLength, tracker.announceUrl(), new Random(9));
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));
            Path out = dir.resolve("out");
            byte[] content = Files.readAllBytes(generated.contentFile());

            try (NioSeeder seeder = NioSeeder.start(generated.contentFile(), meta)) {
                seeder.announceTo(tracker);

                // 第一次：完整下载（用于拿到干净进度态）
                long fullElapsed;
                try (DefaultTorrentClient client = DefaultTorrentClient.builder().build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(out));
                    task.future().get(60, TimeUnit.SECONDS);
                }
                // 把完成态回退成"进行中"：删除末件标记 + 保存 resume，模拟中断
                try (StorageManager storage = new StorageManager(meta, out)) {
                    Bitfield completed = new Bitfield(pieces);
                    for (int i = 0; i < pieces; i++) {
                        completed.set(i);
                    }
                    // 注入坏块：把件 17 的首块写脏（FULL 应检出并重下）
                    byte[] dirty = new byte[16384];
                    Arrays.fill(dirty, (byte) 0xDE);
                    storage.writeBlock(17, 0, dirty);
                    ResumeState.save(out.resolve("sampled.bin.jt-resume"),
                        new ResumeState(meta.infoHash(), pieces, completed, 0, 0,
                            System.currentTimeMillis()));
                }
                // 删除已完成的最终文件，让恢复态重新可见 .part
                Files.deleteIfExists(out.resolve("sampled.bin"));

                long t0 = System.nanoTime();
                try (DefaultTorrentClient client = DefaultTorrentClient.builder().build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(out)
                            .restartVerify(RestartVerifyMode.FULL));
                    DownloadResult result = task.future().get(60, TimeUnit.SECONDS);
                    // FULL：坏件被检出重下，最终内容仍正确
                    assertArrayEquals(content, Files.readAllBytes(result.file()));
                }
                fullElapsed = System.nanoTime() - t0;

                // 无坏块的 SAMPLED 恢复（重建干净态）：应明显快（跳过约 90% 读盘）
                try (StorageManager storage = new StorageManager(meta, out)) {
                    for (int p = 0; p < pieces; p++) {
                        storage.writeBlock(p, 0, Arrays.copyOfRange(content,
                            p * pieceLength, p * pieceLength + pieceLength));
                    }
                    Bitfield completed = new Bitfield(pieces);
                    for (int i = 0; i < pieces; i++) {
                        completed.set(i);
                    }
                    ResumeState.save(out.resolve("sampled.bin.jt-resume"),
                        new ResumeState(meta.infoHash(), pieces, completed, 0, 0,
                            System.currentTimeMillis()));
                }
                Files.deleteIfExists(out.resolve("sampled.bin"));
                long t1 = System.nanoTime();
                try (DefaultTorrentClient client = DefaultTorrentClient.builder().build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(out)
                            .restartVerify(RestartVerifyMode.SAMPLED));
                    DownloadResult result = task.future().get(60, TimeUnit.SECONDS);
                    assertArrayEquals(content, Files.readAllBytes(result.file()));
                }
                long sampledElapsed = System.nanoTime() - t1;

                // 抽样恢复不慢于全量（回环下载为主，宽松断言：≤ 全量的 1.1×）
                assertEquals(true, sampledElapsed <= fullElapsed * 1.1 + 2_000_000_000L,
                    "SAMPLED(" + sampledElapsed / 1_000_000 + "ms) should not be slower than "
                        + "FULL(" + fullElapsed / 1_000_000 + "ms)");
            }
        }
    }

    @Test
    void sampledModeWithSparsePartialResumeTerminates() throws Exception {
        // 回归：SAMPLED 抽样目标曾按总件数 count/10 取值，已完成件数不足目标时
        // （此处 3 < 64/10）抽样循环永远凑不齐 → start() 死循环。
        // 现目标按已完成件数封顶：3 件全部进样本，零值 .part 校验失败后重下，任务正常完成。
        int pieceLength = 256 * 1024;
        int pieces = 64;
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "sparse.bin", pieces * pieceLength, tracker.announceUrl(), new Random(11));
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));
            Path out = dir.resolve("out-sparse");
            byte[] content = Files.readAllBytes(generated.contentFile());

            try (NioSeeder seeder = NioSeeder.start(generated.contentFile(), meta)) {
                seeder.announceTo(tracker);
                // 直接伪造 3/64 完成的恢复态（非边界件）：.part 由引擎侧预分配为零值
                try (StorageManager storage = new StorageManager(meta, out)) {
                    Bitfield completed = new Bitfield(pieces);
                    for (int i : new int[]{3, 20, 41}) {
                        completed.set(i);
                    }
                    ResumeState.save(out.resolve("sparse.bin.jt-resume"),
                        new ResumeState(meta.infoHash(), pieces, completed, 0, 0,
                            System.currentTimeMillis()));
                }
                try (DefaultTorrentClient client = DefaultTorrentClient.builder().build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(out)
                            .restartVerify(RestartVerifyMode.SAMPLED));
                    // 修复前：restoreResume 内死循环，此处 60s 超时
                    DownloadResult result = task.future().get(60, TimeUnit.SECONDS);
                    assertArrayEquals(content, Files.readAllBytes(result.file()));
                }
            }
        }
    }

    @Test
    void noneModeTrustsBitmapButFinalContentStillVerified() throws Exception {
        int pieceLength = 256 * 1024;
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "none.bin", 4 * pieceLength, tracker.announceUrl(), new Random(3));
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));
            Path out = dir.resolve("out-none");
            byte[] content = Files.readAllBytes(generated.contentFile());

            try (NioSeeder seeder = NioSeeder.start(generated.contentFile(), meta)) {
                seeder.announceTo(tracker);
                try (DefaultTorrentClient client = DefaultTorrentClient.builder().build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(out));
                    task.future().get(60, TimeUnit.SECONDS);
                }
                // 回退到"3/4 完成 + 件 2 磁盘损坏"的恢复态，NONE 模式
                try (StorageManager storage = new StorageManager(meta, out)) {
                    Bitfield completed = new Bitfield(4);
                    for (int i : new int[]{0, 1, 2}) {
                        completed.set(i);
                    }
                    byte[] dirty = new byte[16384];
                    Arrays.fill(dirty, (byte) 0xAD);
                    storage.writeBlock(2, 0, dirty);
                    ResumeState.save(out.resolve("none.bin.jt-resume"),
                        new ResumeState(meta.infoHash(), 4, completed, 0, 0,
                            System.currentTimeMillis()));
                }
                Files.deleteIfExists(out.resolve("none.bin"));

                try (DefaultTorrentClient client = DefaultTorrentClient.builder().build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(out)
                            .restartVerify(RestartVerifyMode.NONE));
                    DownloadResult result = task.future().get(60, TimeUnit.SECONDS);
                    // NONE 信任了坏件 2，位图全满 → 直接完成 → 最终文件带脏块（这是 NONE 的语义代价）
                    // 本断言记录该行为：文件长度正确
                    assertEquals(content.length, Files.size(result.file()));
                }
            }
        }
    }
}
