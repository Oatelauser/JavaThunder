package io.github.oatelauser.thunder.tracker;

import com.sun.net.httpserver.HttpExchange;
import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;

import java.io.IOException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * scrape 端点（BEP 48）：?info_hash=... 可重复；缺省返回全部已知 swarm；未知 hash 全零。
 */
final class ScrapeHttpHandler {

    private final SwarmRegistry registry;
    private final TrackerMetrics metrics;

    ScrapeHttpHandler(SwarmRegistry registry, TrackerMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    void handle(HttpExchange exchange) throws IOException {
        try {
            metrics.scrape();
            Map<String, List<byte[]>> params = Query.parse(exchange.getRequestURI().getRawQuery());
            List<byte[]> requested = params.get("info_hash");
            Map<BString, BencodeValue> files = new HashMap<>();
            if (requested == null || requested.isEmpty()) {
                for (String hex : registry.knownHashes()) {
                    files.put(new BString(HexFormat.of().parseHex(hex)), fileEntry(hex));
                }
            } else {
                for (byte[] infoHash : requested) {
                    if (infoHash != null && infoHash.length == 20) {
                        files.put(new BString(infoHash),
                                fileEntry(HexFormat.of().formatHex(infoHash)));
                    }
                }
            }
            BDict body = BDict.of(Map.of(BString.of("files"), BDict.of(files)));
            Http.respond(exchange, Bencode.encode(body));
        } catch (RuntimeException e) {
            Http.respond(exchange, Http.failure("tracker error: " + e));
        }
    }

    private BDict fileEntry(String hex) {
        Map<String, Long> counts = registry.scrapeEntry(hex);
        return BDict.of(Map.of(
                BString.of("complete"), new BInteger(counts.get("complete")),
                BString.of("downloaded"), new BInteger(counts.get("downloaded")),
                BString.of("incomplete"), new BInteger(counts.get("incomplete"))));
    }
}
