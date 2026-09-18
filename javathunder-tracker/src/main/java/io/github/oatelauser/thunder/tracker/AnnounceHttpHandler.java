package io.github.oatelauser.thunder.tracker;

import com.sun.net.httpserver.HttpExchange;
import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * HTTP announce 端点（BEP 3/23）：query 解析 → 领域作用 → bencode 响应。
 */
final class AnnounceHttpHandler {

    private final SwarmRegistry registry;
    private final TrackerMetrics metrics;
    private final int announceIntervalSeconds;

    AnnounceHttpHandler(SwarmRegistry registry, TrackerMetrics metrics, int announceIntervalSeconds) {
        this.registry = registry;
        this.metrics = metrics;
        this.announceIntervalSeconds = announceIntervalSeconds;
    }

    void handle(HttpExchange exchange) throws IOException {
        try {
            Map<String, List<byte[]>> params = Query.parse(exchange.getRequestURI().getRawQuery());
            byte[] infoHash = Query.first(params, "info_hash");
            byte[] portBytes = Query.first(params, "port");
            int port = parsePort(portBytes);
            if (infoHash == null || infoHash.length != 20 || port < 0) {
                Http.respond(exchange, Http.failure("invalid announce"));
                return;
            }
            metrics.httpAnnounce();
            String denied = registry.denyReason(infoHash);
            if (denied != null) {
                Http.respond(exchange, Http.failure(denied));
                return;
            }
            String event = Query.textOf(Query.first(params, "event"));
            SwarmRegistry.SwarmView view = registry.apply(new SwarmRegistry.Announce(
                    infoHash,
                    new InetSocketAddress(exchange.getRemoteAddress().getAddress().getHostAddress(), port),
                    Query.seederByLeft(Query.first(params, "left")),
                    "stopped".equals(event), "completed".equals(event), -1));
            Http.respond(exchange, announceResponse(view));
        } catch (RuntimeException e) {
            Http.respond(exchange, Http.failure("tracker error: " + e));
        }
    }

    private static int parsePort(@Nullable byte[] portBytes) {
        if (portBytes == null) {
            return -1;
        }
        try {
            return Integer.parseInt(new String(portBytes, StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 标准 announce 响应：interval/complete/incomplete/peers（compact）。
     */
    private byte[] announceResponse(SwarmRegistry.SwarmView view) {
        return Bencode.encode(BDict.of(Map.of(
                BString.of("complete"), new BInteger(view.seeders()),
                BString.of("incomplete"), new BInteger(view.leechers()),
                BString.of("interval"), new BInteger(announceIntervalSeconds),
                BString.of("peers"), new BString(view.peersCompact()))));
    }
}
