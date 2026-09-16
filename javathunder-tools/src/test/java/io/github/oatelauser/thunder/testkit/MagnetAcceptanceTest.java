package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.MagnetUri;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 磁力链接验收（B1）：无 .torrent 文件，仅凭 info-hash + tracker 完成下载。 */
class MagnetAcceptanceTest {

    @TempDir
    Path dir;

    @Test
    void downloadsViaMagnetUri() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "magnet.bin", 600_000, tracker.announceUrl(), new Random(51));
            byte[] torrentBytes = Files.readAllBytes(generated.torrentFile());
            TorrentMetadata meta = TorrentParser.parse(torrentBytes);

            try (MetadataSeeder seeder = MetadataSeeder.start(
                generated.contentFile(), meta, torrentBytes)) {
                seeder.announceTo(tracker);
                try (TorrentClient client = TorrentClient.builder()
                        .transport(Transports.select()).build()) {

                    String magnetUri = "magnet:?xt=urn:btih:"
                        + HexFormat.of().formatHex(meta.infoHash())
                        + "&dn=magnet.bin&tr=" + URLEncoder.encode(
                            tracker.announceUrl(), StandardCharsets.UTF_8);
                    DownloadTask task = client.download(MagnetUri.parse(magnetUri),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")));
                    DownloadResult result = task.future().get(90, TimeUnit.SECONDS);

                    assertEquals(TaskState.COMPLETED, task.state());
                    assertArrayEquals(Files.readAllBytes(generated.contentFile()),
                        Files.readAllBytes(result.file()));
                }
            }
        }
    }
}
