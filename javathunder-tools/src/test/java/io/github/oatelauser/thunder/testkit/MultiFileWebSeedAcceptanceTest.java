package io.github.oatelauser.thunder.testkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.TorrentClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 多文件 WebSeed 端到端验收（BEP 19 目录形态）：url-list 指向 HTTP 目录 base，
 * 引擎按 种子内相对路径 + 文件内 Range 逐段取回拼件——纯 WebSeed 种子（无 tracker
 * 无 Peer）完成下载并字节级比对。文件尺寸特意非对齐（件跨文件边界 → 多段拼接路径）。
 * 端口说明：listenPort 分段基址只是随机抖动起点，并非跨测试类的防撞约定
 * （窄带彼此重叠、且落在它类的宽带 17000–37000 内），勿据此新增"端口分配表"。
 */
class MultiFileWebSeedAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final int PIECE_LENGTH = 16 * 1024;

    /** 目录服务：/root/<相对路径> 返回对应文件字节（支持 Range，206 分片）。 */
    private HttpServer startDirectoryServer(Map<String, byte[]> files) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> serve(exchange, files));
        server.start();
        return server;
    }

    private void serve(HttpExchange exchange, Map<String, byte[]> files) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath().substring(1); // 去前导 "/"
            byte[] content = files.get(path);
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            int from = 0;
            int to = content.length - 1;
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-");
                from = Integer.parseInt(parts[0]);
                to = Integer.parseInt(parts[1]);
            }
            byte[] slice = Arrays.copyOfRange(content, from, to + 1);
            exchange.getResponseHeaders().add("Content-Range",
                    "bytes " + from + "-" + to + "/" + content.length);
            exchange.sendResponseHeaders(206, slice.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(slice);
            }
        } finally {
            exchange.close();
        }
    }

    /** 非对齐三文件布局（件 0 与件 3 各跨两个文件 → 多段拼接路径）。 */
    private static List<List<Object>> bundleSpecs() {
        return List.of(
                List.of(List.of("readme.txt"), 10000),
                List.of(List.of("docs", "manual.pdf"), 40000),
                List.of(List.of("cover.png"), 20000));
    }

    private static void registerFiles(Map<String, byte[]> served, Path root) throws IOException {
        served.put("root/readme.txt", Files.readAllBytes(root.resolve("readme.txt")));
        served.put("root/docs/manual.pdf",
                Files.readAllBytes(root.resolve("docs").resolve("manual.pdf")));
        served.put("root/cover.png", Files.readAllBytes(root.resolve("cover.png")));
    }

    @Test
    void multiFileTorrentDownloadsViaWebSeedDirectory() throws Exception {
        Random random = new Random(123);
        Map<String, byte[]> served = new ConcurrentHashMap<>();
        HttpServer server = startDirectoryServer(served);
        try {
            TorrentGenerator.GeneratedMultiFileTorrent seed = TorrentGenerator.generateMultiFile(
                    tempDir, "ws-bundle", bundleSpecs(), PIECE_LENGTH, /*announce=*/ null,
                    List.of("http://127.0.0.1:" + server.getAddress().getPort() + "/root/"),
                    random);
            registerFiles(served, seed.rootDir());

            try (TorrentClient client = TorrentClient.builder()
                    .transport(Transports.select())
                    .listenPort(25400 + random.nextInt(3000))
                    .build()) {
                DownloadResult result = client.download(seed.torrentFile(),
                                DownloadOptions.defaults().targetDir(tempDir.resolve("dlWs")))
                        .future().get(90, TimeUnit.SECONDS);

                assertArrayEquals(Files.readAllBytes(seed.rootDir().resolve("readme.txt")),
                        Files.readAllBytes(result.file().resolve("readme.txt")));
                assertArrayEquals(Files.readAllBytes(
                                seed.rootDir().resolve("docs").resolve("manual.pdf")),
                        Files.readAllBytes(result.file().resolve("docs").resolve("manual.pdf")));
                assertArrayEquals(Files.readAllBytes(seed.rootDir().resolve("cover.png")),
                        Files.readAllBytes(result.file().resolve("cover.png")),
                        "跨界末文件完整（件 3 由 manual 尾段 + cover 头段拼接）");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingFileNeverYieldsCorruptCompletion() throws Exception {
        // 目录缺 manual.pdf：该文件的段全部 404 → 源熔断、WebSeed 通道停；无 Peer 兜底
        // → 任务永不完成（绝不拼出坏件冒充成功）。以超时为通过判据。
        Random random = new Random(124);
        Map<String, byte[]> served = new ConcurrentHashMap<>();
        HttpServer server = startDirectoryServer(served);
        try {
            TorrentGenerator.GeneratedMultiFileTorrent seed = TorrentGenerator.generateMultiFile(
                    tempDir, "ws-missing", bundleSpecs(), PIECE_LENGTH, null,
                    List.of("http://127.0.0.1:" + server.getAddress().getPort() + "/root/"),
                    random);
            registerFiles(served, seed.rootDir());
            served.remove("root/docs/manual.pdf"); // 挖掉中间文件

            try (TorrentClient client = TorrentClient.builder()
                    .transport(Transports.select())
                    .listenPort(25800 + random.nextInt(3000))
                    .build()) {
                assertThrows(TimeoutException.class, () -> client.download(
                                seed.torrentFile(),
                                DownloadOptions.defaults().targetDir(tempDir.resolve("dlMiss")))
                        .future().get(45, TimeUnit.SECONDS),
                        "缺源任务应保持未完成（无坏件假完成）");
            }
        } finally {
            server.stop(0);
        }
    }
}
