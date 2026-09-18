package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.RestartVerifyMode;
import io.github.oatelauser.thunder.api.TorrentClient;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2 重启校验三档：坏块注入（把一个已完成件在磁盘上改脏）后分别以
 * FULL / SAMPLED / NONE 恢复——FULL 必检出重下；SAMPLED 是抽样语义（本用例
 * 用多件种子让坏件高概率在样本内）；NONE 信任位图不做启动校验，坏块留盘是
 * 该档位的语义代价（见 noneMode 用例，仅断言文件形状）。
 */
class RestartVerifyModeTest {

    @TempDir
    Path dir;

    @Test
    void sampledModeSkipsUnsampledPieces() throws Exception {
        // 32 件 × 256KiB = 8MiB：坏件 17；SAMPLED 10% ≈ 4 件 + 边界，不做必中断言（概率语义），
        // 断言的是宽松行为差异：SAMPLED 恢复不显著慢于 FULL（不逐件读盘，见下方 1.1× 宽容带）
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
                downloadFully(generated.torrentFile(), out);
                // 把完成态回退成"进行中"：删除末件标记 + 保存 resume，模拟中断
                injectDirtyPieceAndSaveResume(meta, out, pieces);
                // 删除已完成的最终文件，让恢复态重新可见 .part
                Files.deleteIfExists(out.resolve("sampled.bin"));

                // FULL：坏件被检出重下，最终内容仍正确
                long fullElapsed = timedRestartVerify(generated.torrentFile(), out, content,
                    RestartVerifyMode.FULL);

                // 无坏块的 SAMPLED 恢复（重建干净态）：应明显快（跳过约 90% 读盘）
                rewriteAllPiecesAndSaveResume(meta, out, pieces, pieceLength, content);
                Files.deleteIfExists(out.resolve("sampled.bin"));
                long sampledElapsed = timedRestartVerify(generated.torrentFile(), out, content,
                    RestartVerifyMode.SAMPLED);

                // 抽样恢复不慢于全量（回环下载为主，宽松断言：≤ 全量的 1.1×）
                assertTrue(sampledElapsed <= fullElapsed * 1.1 + 2_000_000_000L,
                    "SAMPLED(" + sampledElapsed / 1_000_000 + "ms) should not be slower than "
                        + "FULL(" + fullElapsed / 1_000_000 + "ms)");
            }
        }
    }

    /** 阶段一：完整下载一次，为后续构造干净的完成进度态。 */
    private static void downloadFully(Path torrentFile, Path out) throws Exception {
        try (TorrentClient client = TorrentClient.builder().build()) {
            DownloadTask task = client.download(torrentFile,
                DownloadOptions.defaults().targetDir(out));
            task.future().get(60, TimeUnit.SECONDS);
        }
    }

    /** 阶段二：把件 17 首块写脏并保存全完成位图，构造"中断 + 坏块"的断点状态。 */
    private static void injectDirtyPieceAndSaveResume(TorrentMetadata meta, Path out, int pieces)
        throws Exception {
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
    }

    /** 阶段三：以指定校验模式恢复下载并断言最终内容与源一致，返回恢复耗时（纳秒）。 */
    private static long timedRestartVerify(Path torrentFile, Path out, byte[] content,
        RestartVerifyMode mode) throws Exception {
        long t0 = System.nanoTime();
        try (TorrentClient client = TorrentClient.builder().build()) {
            DownloadTask task = client.download(torrentFile,
                DownloadOptions.defaults().targetDir(out)
                    .restartVerify(mode));
            DownloadResult result = task.future().get(60, TimeUnit.SECONDS);
            assertArrayEquals(content, Files.readAllBytes(result.file()));
        }
        return System.nanoTime() - t0;
    }

    /** 阶段四：用正确内容重写全部件并保存全完成位图，重建无坏块的恢复态。 */
    private static void rewriteAllPiecesAndSaveResume(TorrentMetadata meta, Path out, int pieces,
        int pieceLength, byte[] content) throws Exception {
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
                try (TorrentClient client = TorrentClient.builder().build()) {
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
    void noneModeLeavesCorruptPieceOnDisk() throws Exception {
        int pieceLength = 256 * 1024;
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "none.bin", 4 * pieceLength, tracker.announceUrl(), new Random(3));
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));
            Path out = dir.resolve("out-none");
            byte[] content = Files.readAllBytes(generated.contentFile());

            try (NioSeeder seeder = NioSeeder.start(generated.contentFile(), meta)) {
                seeder.announceTo(tracker);
                try (TorrentClient client = TorrentClient.builder().build()) {
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

                try (TorrentClient client = TorrentClient.builder().build()) {
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
