package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 手动性能探针（CI 中跳过）：同一台机器上对比三个基线，
 * 用于回答"引擎瓶颈在哪一层"。运行时输出各行 MB/s。
 */
@Tag("perf")
class LoopbackThroughputProbeTest {

    @TempDir
    Path dir;

    private static final int MEGA = 1024 * 1024;
    private static final int SIZE_MB = 128;

    @Test
    void probeEngineAgainstBaselines() throws Exception {
        byte[] data = new byte[SIZE_MB * MEGA];
        new Random(1).nextBytes(data);

        // 基线 1：SHA-1（读回校验的纯计算成本）
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        long t = System.nanoTime();
        sha1.update(data, 0, data.length);
        sha1.digest();
        double sha1Mbps = SIZE_MB / elapsedSeconds(t);
        System.out.printf("PROBE sha1            %6.0f MB/s%n", sha1Mbps);

        // 基线 2：顺序写盘（引擎落盘的纯 IO 成本）
        Path sink = dir.resolve("sink.bin");
        t = System.nanoTime();
        try (FileChannel channel = FileChannel.open(sink,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
        double writeMbps = SIZE_MB / elapsedSeconds(t);
        System.out.printf("PROBE sequential-write %6.0f MB/s%n", writeMbps);

        // 引擎：回环完整下载（对称 NIO 对端，消除逐帧阻塞测量天花板）
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "payload.bin", data.length, tracker.announceUrl(), new Random(1));
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));
            try (NioSeeder seeder = NioSeeder.start(generated.contentFile(), meta)) {
                seeder.announceTo(tracker);
                try (DefaultTorrentClient client = DefaultTorrentClient.builder()
                        .transportFactory(Transports.fromSystemProperty()).build()) {
                    t = System.nanoTime();
                    DownloadTask task = client.download(generated.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")));
                    // 90s 超时：引擎若饥饿挂死，宁可失败暴露，绝不无限 park
                    DownloadResult result = task.future().get(90, TimeUnit.SECONDS);
                    double engineMbps = SIZE_MB / elapsedSeconds(t);
                    System.out.printf("PROBE engine-loopback  %6.0f MB/s  (%d bytes, %.1fs)%n",
                        engineMbps, result.bytes(), result.elapsed().toMillis() / 1000.0);
                }
            }
        }
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }
}
