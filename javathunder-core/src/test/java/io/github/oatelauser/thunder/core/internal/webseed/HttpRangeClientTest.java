package io.github.oatelauser.thunder.core.internal.webseed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpRangeClient 直测（BEP 19）：本地 HTTP 服务对拍 206/200/500/416 各响应形态，
 * 退避用可注入时钟推进（不真实等待）。
 */
class HttpRangeClientTest {

    private static final int PIECE_LENGTH = 64 * 1024;
    private static final int TOTAL_LENGTH = PIECE_LENGTH * 2 + 1234; // 末件非整长

    private HttpServer server;
    private byte[] content;
    private final AtomicReference<String> mode = new AtomicReference<>("range-ok");
    private final AtomicLong clock = new AtomicLong(1_000_000);

    @BeforeEach
    void startServer() throws IOException {
        content = new byte[TOTAL_LENGTH];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31 + 7);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** 按 mode 分派：range-ok（标准 206）/ always-200（忽略 Range 回全量）/ always-500 / 416。 */
    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        int status;
        if ("always-200".equals(mode.get())) {
            status = 200;
            body = content;
        } else if ("always-500".equals(mode.get())) {
            status = 500;
            body = new byte[0];
        } else if ("416".equals(mode.get())) {
            status = 416;
            body = new byte[0];
        } else {
            long[] range = parseRange(exchange.getRequestHeaders().getFirst("Range"));
            status = 206;
            body = new byte[(int) (range[1] - range[0] + 1)];
            System.arraycopy(content, (int) range[0], body, 0, body.length);
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + range[0] + "-" + range[1] + "/" + TOTAL_LENGTH);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static long[] parseRange(String header) {
        // "bytes=a-b"（本测试唯一发出的形态）
        String spec = header.substring("bytes=".length());
        int dash = spec.indexOf('-');
        return new long[]{Long.parseLong(spec.substring(0, dash)), Long.parseLong(spec.substring(dash + 1))};
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
    }

    private HttpRangeClient client() {
        return new HttpRangeClient(List.of(url()), TOTAL_LENGTH, clock::get);
    }

    @Test
    void fetchesPieceBytesViaRangeRequest() throws IOException {
        HttpRangeClient client = client();
        byte[] piece1 = client.fetchPiece(1, PIECE_LENGTH);
        assertArrayEquals(Arrays.copyOfRange(content, PIECE_LENGTH, 2 * PIECE_LENGTH), piece1);
    }

    @Test
    void lastPieceIsTruncatedToRemainingLength() throws IOException {
        HttpRangeClient client = client();
        byte[] last = client.fetchPiece(2, PIECE_LENGTH);
        assertEquals(TOTAL_LENGTH - 2 * PIECE_LENGTH, last.length);
        assertArrayEquals(Arrays.copyOfRange(content, 2 * PIECE_LENGTH, TOTAL_LENGTH), last);
    }

    @Test
    void rangeIgnoringServerDisablesSourceImmediately() {
        mode.set("always-200");
        HttpRangeClient client = client();
        assertThrows(IOException.class, () -> client.fetchPiece(0, PIECE_LENGTH));
        assertTrue(client.allSourcesDisabled(), "200 全量响应应立即熔断该源");
    }

    @Test
    void rangeNotSatisfiableDisablesSourceImmediately() {
        mode.set("416");
        HttpRangeClient client = client();
        assertThrows(IOException.class, () -> client.fetchPiece(0, PIECE_LENGTH));
        assertTrue(client.allSourcesDisabled(), "416 应立即熔断该源");
    }

    @Test
    void transientFailureBacksOffThenCircuitsAfterTwoAttempts() {
        mode.set("always-500");
        HttpRangeClient client = client();
        // 第一次失败：进入 2s 退避，未熔断
        assertThrows(IOException.class, () -> client.fetchPiece(0, PIECE_LENGTH));
        assertFalse(client.allSourcesDisabled());
        // 退避窗口内再试：源被跳过，直接抛"无可用源"
        assertThrows(IOException.class, () -> client.fetchPiece(0, PIECE_LENGTH));
        assertFalse(client.allSourcesDisabled(), "退避中不算熔断");
        // 推进时钟过退避窗口后第二次失败：连续 2 次 → 熔断
        clock.addAndGet(3_000);
        assertThrows(IOException.class, () -> client.fetchPiece(0, PIECE_LENGTH));
        assertTrue(client.allSourcesDisabled());
    }

    @Test
    void failsOverToSecondSourceWhenFirstFails() throws IOException {
        mode.set("always-500");
        HttpServer healthy = null;
        try {
            healthy = startHealthyMirror();
            HttpRangeClient client = new HttpRangeClient(
                    List.of(url(), mirrorUrl(healthy)), TOTAL_LENGTH, clock::get);
            byte[] piece = client.fetchPiece(0, PIECE_LENGTH);
            assertArrayEquals(Arrays.copyOfRange(content, 0, PIECE_LENGTH), piece);
        } finally {
            if (healthy != null) {
                healthy.stop(0);
            }
        }
    }

    private HttpServer startHealthyMirror() throws IOException {
        HttpServer mirror = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mirror.createContext("/file", exchange -> {
            long[] range = parseRange(exchange.getRequestHeaders().getFirst("Range"));
            byte[] body = new byte[(int) (range[1] - range[0] + 1)];
            System.arraycopy(content, (int) range[0], body, 0, body.length);
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + range[0] + "-" + range[1] + "/" + TOTAL_LENGTH);
            exchange.sendResponseHeaders(206, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        mirror.start();
        return mirror;
    }

    private static String mirrorUrl(HttpServer mirror) {
        return "http://127.0.0.1:" + mirror.getAddress().getPort() + "/file";
    }
}
