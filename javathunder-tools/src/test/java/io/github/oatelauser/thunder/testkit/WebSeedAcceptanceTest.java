package io.github.oatelauser.thunder.testkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WebSeed（BEP 19）验收：本地 HTTP 源（JDK httpserver）与真实引擎对拍四场景——
 * 纯 HTTP 源无 tracker 下载、HTTP 源不支持 Range 回退 Peer、HTTP 源数据损坏
 * （坏件熔断）回退 Peer、Peer+HTTP 混合完成。源先起（拿端口）→ 带 url-list 造种 →
 * 内容回填源，保证种子哈希与服务内容一致。
 */
class WebSeedAcceptanceTest {

    private static final int SIZE = 512 * 1024;
    private static final int PIECE_LENGTH = 64 * 1024;

    @TempDir
    Path tempDir;

    /** HTTP 源形态：range-ok 标准 206 / corrupt 206 但内容损坏 / always-200 忽略 Range。 */
    private final AtomicReference<String> mode = new AtomicReference<>("range-ok");
    private byte[] content;

    /** 起本地 HTTP 源（内容稍后由用例经 content 字段回填——端口要在造种前确定）。 */
    private HttpServer startSource() throws IOException {
        content = new byte[0];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", this::serve);
        server.start();
        return server;
    }

    private void serve(HttpExchange exchange) throws IOException {
        if ("always-200".equals(mode.get())) {
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(content);
            }
            return;
        }
        long[] range = parseRange(exchange.getRequestHeaders().getFirst("Range"));
        int from = (int) range[0];
        int to = (int) range[1];
        byte[] body = new byte[to - from + 1];
        System.arraycopy(content, from, body, 0, body.length);
        if ("corrupt".equals(mode.get())) {
            body[0] ^= 0x5A; // 每个响应都损坏：触发 WebSeed 坏件熔断
        }
        exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + content.length);
        exchange.sendResponseHeaders(206, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static long[] parseRange(String header) {
        String spec = header.substring("bytes=".length());
        int dash = spec.indexOf('-');
        return new long[]{Long.parseLong(spec.substring(0, dash)), Long.parseLong(spec.substring(dash + 1))};
    }

    /** 造种（url-list 指向本地源）并把内容回填给源。 */
    private TorrentGenerator.GeneratedTorrent seedAgainst(HttpServer source, String name,
            String announceUrl, Random random) throws IOException {
        String url = "http://127.0.0.1:" + source.getAddress().getPort() + "/file";
        TorrentGenerator.GeneratedTorrent seed = TorrentGenerator.generate(tempDir, name,
                SIZE, PIECE_LENGTH, announceUrl, List.of(url), random);
        content = Files.readAllBytes(seed.contentFile());
        return seed;
    }

    private DownloadResult download(TorrentGenerator.GeneratedTorrent seed, Random random) throws Exception {
        try (TorrentClient client = TorrentClient.builder()
                .transport(Transports.select())
                .listenPort(17000 + random.nextInt(20000))
                .build()) {
            return client.download(seed.torrentFile(),
                            DownloadOptions.defaults().targetDir(tempDir.resolve("dl-" + seed.contentFile().getFileName())))
                    .future().get(90, TimeUnit.SECONDS);
        }
    }

    @Test
    void pureWebSeedCompletesWithoutTrackerOrPeers() throws Exception {
        Random random = new Random(7);
        HttpServer source = startSource();
        try {
            TorrentGenerator.GeneratedTorrent seed = seedAgainst(source, "pure-webseed.bin", null, random);
            assertEquals(1, TorrentParser.parse(Files.readAllBytes(seed.torrentFile())).webSeeds().size());
            DownloadResult result = download(seed, random);
            assertArrayEquals(content, Files.readAllBytes(result.file()));
        } finally {
            source.stop(0);
        }
    }

    @Test
    void rangeIgnoringSourceFallsBackToPeers() throws Exception {
        Random random = new Random(11);
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            HttpServer source = startSource();
            try {
                mode.set("always-200");
                TorrentGenerator.GeneratedTorrent seed =
                        seedAgainst(source, "no-range.bin", tracker.announceUrl(), random);
                try (FakeSeeder seeder = FakeSeeder.start(seed.contentFile(),
                        TorrentParser.parse(Files.readAllBytes(seed.torrentFile())))) {
                    seeder.announceTo(tracker);
                    DownloadResult result = download(seed, random);
                    assertArrayEquals(content, Files.readAllBytes(result.file()));
                }
            } finally {
                source.stop(0);
            }
        }
    }

    @Test
    void corruptWebSeedFallsBackToPeers() throws Exception {
        Random random = new Random(13);
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            HttpServer source = startSource();
            try {
                mode.set("corrupt");
                TorrentGenerator.GeneratedTorrent seed =
                        seedAgainst(source, "corrupt.bin", tracker.announceUrl(), random);
                try (FakeSeeder seeder = FakeSeeder.start(seed.contentFile(),
                        TorrentParser.parse(Files.readAllBytes(seed.torrentFile())))) {
                    seeder.announceTo(tracker);
                    DownloadResult result = download(seed, random);
                    assertArrayEquals(content, Files.readAllBytes(result.file()));
                }
            } finally {
                source.stop(0);
            }
        }
    }

    @Test
    void healthyWebSeedAndPeersCompleteTogether() throws Exception {
        Random random = new Random(17);
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            HttpServer source = startSource();
            try {
                TorrentGenerator.GeneratedTorrent seed =
                        seedAgainst(source, "mixed.bin", tracker.announceUrl(), random);
                try (FakeSeeder seeder = FakeSeeder.start(seed.contentFile(),
                        TorrentParser.parse(Files.readAllBytes(seed.torrentFile())))) {
                    seeder.announceTo(tracker);
                    DownloadResult result = download(seed, random);
                    assertArrayEquals(content, Files.readAllBytes(result.file()));
                }
            } finally {
                source.stop(0);
            }
        }
    }
}
