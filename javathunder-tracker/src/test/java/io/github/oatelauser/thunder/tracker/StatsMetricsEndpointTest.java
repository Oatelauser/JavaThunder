package io.github.oatelauser.thunder.tracker;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可观测端点：/stats 人类可读 HTML（全局汇总 + 每 info-hash 表）；
 * /metrics Prometheus 文本格式（HELP/TYPE 齐备、样例行语法合法、数值正确）。
 */
class StatsMetricsEndpointTest {

    private static final Pattern SAMPLE_LINE =
            Pattern.compile("^[a-zA-Z_:][a-zA-Z0-9_:]*(\\{[^}]*})? \\d+$");

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void statsPageRendersHtmlWithGlobalAndPerSwarmTables() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            byte[] infoHash = infoHash(1);
            tracker.register(infoHash, 15300);                                   // seeder
            announce(tracker, infoHash(2), 15301, 500);                          // leecher

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(tracker.announceUrl()
                            .replace("/announce", "/stats"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
            String body = response.body();
            assertTrue(body.contains("<!DOCTYPE html>"));
            assertTrue(body.contains("active swarms"));
            assertTrue(body.contains("announces (http / udp)"), "全局汇总需含 announce 计数");
            // 每 swarm 表：hex 行带 seeders/leechers/downloads
            assertTrue(body.contains(hex(1)));
            assertTrue(body.contains(hex(2)));
            assertTrue(body.contains("seeders"));
            assertTrue(body.contains("downloads (completed)"));
        }
    }

    @Test
    void metricsExposeValidPrometheusTextWithCorrectValues() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            // 先取一次空态：全零、格式合法
            HttpResponse<String> empty = getMetrics(tracker);
            assertEquals(0, gaugeOf(empty.body(), "javathunder_tracker_active_swarms"));
            assertEquals("0", sampleValue(empty.body(),
                    Pattern.compile("^javathunder_tracker_announces_total\\{transport=\"http\"} (\\d+)$")));

            byte[] hash1 = infoHash(1);
            tracker.register(hash1, 15300);                 // 1 seeder
            announce(tracker, infoHash(2), 15301, 500);     // 1 leecher（经 /announce）
            // 触发一次 scrape 计数
            http.send(HttpRequest.newBuilder(URI.create(tracker.announceUrl()
                            .replace("/announce", "/scrape"))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            HttpResponse<String> response = getMetrics(tracker);
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
            String body = response.body();

            // 格式合法性：每个 HELP/TYPE 后跟至少一条样例行，样例行全部匹配 Prometheus 语法
            for (String family : new String[]{
                    "javathunder_tracker_swarm_peers",
                    "javathunder_tracker_swarm_downloads_total",
                    "javathunder_tracker_announces_total",
                    "javathunder_tracker_scrapes_total",
                    "javathunder_tracker_active_swarms"}) {
                assertTrue(body.contains("# HELP " + family + " "), "missing HELP for " + family);
                assertTrue(body.contains("# TYPE " + family + " "), "missing TYPE for " + family);
            }
            for (String line : body.split("\n")) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    assertTrue(SAMPLE_LINE.matcher(line).matches(), "非法样例行: " + line);
                }
            }

            // 数值：swarm_peers 按 role 标注；announces/scrapes 计数；活跃 swarm 数
            assertEquals("1", sampleValue(body, Pattern.compile(
                    "^javathunder_tracker_swarm_peers\\{role=\"seed\",info_hash=\"" + hex(1) + "\"} (\\d+)$")));
            assertEquals("1", sampleValue(body, Pattern.compile(
                    "^javathunder_tracker_swarm_peers\\{role=\"leech\",info_hash=\"" + hex(2) + "\"} (\\d+)$")));
            assertEquals("1", sampleValue(body, Pattern.compile(
                    "^javathunder_tracker_announces_total\\{transport=\"http\"} (\\d+)$")));
            assertEquals("1", sampleValue(body, Pattern.compile(
                    "^javathunder_tracker_scrapes_total (\\d+)$")));
            assertEquals(2, gaugeOf(body, "javathunder_tracker_active_swarms"));
            assertEquals("0", sampleValue(body, Pattern.compile(
                    "^javathunder_tracker_announces_total\\{transport=\"udp\"} (\\d+)$")));
        }
    }

    // ---- helpers ----

    private HttpResponse<String> getMetrics(EmbeddedTracker tracker) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(tracker.announceUrl()
                        .replace("/announce", "/metrics"))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 无标签 gauge 的数值。 */
    private static int gaugeOf(String body, String metric) {
        return Integer.parseInt(sampleValue(body, Pattern.compile("^" + metric + " (\\d+)$")));
    }

    private static String sampleValue(String body, Pattern linePattern) {
        for (String line : body.split("\n")) {
            java.util.regex.Matcher matcher = linePattern.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        throw new AssertionError("no sample matches: " + linePattern.pattern() + "\n" + body);
    }

    private void announce(EmbeddedTracker tracker, byte[] infoHash, int port, long left)
            throws Exception {
        StringBuilder url = new StringBuilder(tracker.announceUrl());
        url.append("?info_hash=").append(percentEncode(infoHash));
        url.append("&peer_id=").append(percentEncode(new byte[20]));
        url.append("&port=").append(port).append("&left=").append(left);
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(url.toString())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
    }

    private static byte[] infoHash(int seed) {
        byte[] hash = new byte[20];
        new Random(seed).nextBytes(hash);
        return hash;
    }

    private static String hex(int seed) {
        return java.util.HexFormat.of().formatHex(infoHash(seed));
    }

    private static String percentEncode(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 3);
        for (byte b : raw) {
            out.append('%').append(String.format("%02X", b));
        }
        return out.toString();
    }
}
