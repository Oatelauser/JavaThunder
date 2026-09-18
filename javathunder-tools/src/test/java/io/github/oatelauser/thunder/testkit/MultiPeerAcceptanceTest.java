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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 多 Peer 并发：N 个 seeder 同时供种，验证调度跨 Peer 分片正确且吞吐聚合。
 * 参数化（用于扩展曲线测量）：
 * <ul>
 *   <li>{@code -DmultiPeer.mb=128}：payload 尺寸（默认 1 → 1.5MB 小尺寸跑正确性）</li>
 *   <li>{@code -DmultiPeer.seeders=16}：并发 seeder 数（默认 4，保持既有行为）</li>
 *   <li>{@code -Djavathunder.transport=nio}：引擎传输与对端类型；缺省 nio——性能探针
 *       默认须与 {@link Transports#select()} 的生产缺省同臂（nio→NioSeeder），显式传
 *       {@code -Djavathunder.transport=blocking} 才走参照臂（FakeSeeder）</li>
 * </ul>
 */
class MultiPeerAcceptanceTest {

    @TempDir
    Path dir;

    /** 对端抽象：FakeSeeder（逐帧阻塞）与 NioSeeder（事件循环）对外同构。 */
    private interface Seeder extends AutoCloseable {
        void announceTo(EmbeddedTracker tracker);

        @Override
        void close();
    }

    @Test
    void downloadsFromConcurrentSeeders() throws Exception {
        // mb=1 是"正确性小尺寸"特殊档（1.5MB）；显式传大值跑扩展曲线测量
        int mb = Integer.getInteger("multiPeer.mb", 1);
        int sizeBytes = mb == 1 ? 1_500_000 : mb * 1024 * 1024;
        int seederCount = Integer.getInteger("multiPeer.seeders", 4);
        // 与 Transports.select() 同臂判定：缺省 nio（对端 NioSeeder），仅显式 blocking 走 FakeSeeder
        boolean nio = !"blocking".equalsIgnoreCase(System.getProperty("javathunder.transport", "nio"));
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "multi.bin", sizeBytes, tracker.announceUrl(), new Random(99));
            TorrentMetadata meta =
                TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));

            List<Seeder> seeders = new ArrayList<>(seederCount);
            try {
                for (int i = 0; i < seederCount; i++) {
                    Seeder seeder = nio ? startNio(generated.contentFile(), meta)
                        : startFake(generated.contentFile(), meta);
                    seeders.add(seeder);
                    seeder.announceTo(tracker);
                }

                try (TorrentClient client = TorrentClient.builder()
                        .transport(Transports.select())
                        .maxPeersPerTask(50).build()) {
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")));
                    DownloadResult result = task.future().get(60, TimeUnit.SECONDS);

                    assertEquals(TaskState.COMPLETED, task.state());
                    assertArrayEquals(Files.readAllBytes(generated.contentFile()),
                        Files.readAllBytes(result.file()));
                    double seconds = result.elapsed().toNanos() / 1e9;
                    System.out.printf(
                        "MULTI-PEER %d-seeders: %d bytes in %.2fs = %.0f MB/s aggregate%n",
                        seederCount, result.bytes(), seconds, result.bytes() / 1048576.0 / seconds);
                }
            } finally {
                for (int i = seeders.size() - 1; i >= 0; i--) {
                    seeders.get(i).close();
                }
            }
        }
    }

    private Seeder startNio(Path contentFile, TorrentMetadata meta) throws IOException {
        NioSeeder seeder = NioSeeder.start(contentFile, meta);
        return new Seeder() {
            @Override
            public void announceTo(EmbeddedTracker tracker) {
                seeder.announceTo(tracker);
            }

            @Override
            public void close() {
                seeder.close();
            }
        };
    }

    private Seeder startFake(Path contentFile, TorrentMetadata meta) throws IOException {
        FakeSeeder seeder = FakeSeeder.start(contentFile, meta);
        return new Seeder() {
            @Override
            public void announceTo(EmbeddedTracker tracker) {
                seeder.announceTo(tracker);
            }

            @Override
            public void close() {
                seeder.close();
            }
        };
    }
}
