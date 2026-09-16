package io.github.oatelauser.thunder.tracker;

import com.sun.net.httpserver.HttpExchange;
import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * HTTP 端点公共响应工具：bencode（tracker 默认）与文本（stats/metrics）。
 */
final class Http {

    private Http() {
    }

    static void respond(HttpExchange exchange, byte[] body, @Nullable String contentType)
            throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static void respond(HttpExchange exchange, byte[] bencodedBody) throws IOException {
        respond(exchange, bencodedBody, null);
    }

    static void respondText(HttpExchange exchange, String body, String contentType)
            throws IOException {
        respond(exchange, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    static byte[] failure(String reason) {
        return Bencode.encode(BDict.of(Map.of(BString.of("failure reason"), BString.of(reason))));
    }
}
