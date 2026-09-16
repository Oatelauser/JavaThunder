package io.github.oatelauser.thunder.tracker;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEP 48 scrape：注册若干 peer 后 complete/downloaded/incomplete 数值正确；
 * downloaded 在 completed 事件或首次 left=0 时各累计一次、不重复计数；
 * 未知 hash 返回全零条目；多 info_hash 一次查询；无参数返回全部 swarm。
 */
class ScrapeEndpointTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void scrapeReflectsSwarmStateAndDownloadCounters() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            byte[] hashA = infoHash(1);
            byte[] hashB = infoHash(2);
            // swarm A：S 直接以 left=0 做种（首次 left=0 → downloaded+1）
            announce(tracker, hashA, 15900, 0, "started");
            // L 先做 leecher
            announce(tracker, hashA, 15901, 1000, "started");
            assertEquals(1, scrape(tracker, hashA).downloaded());
            // L 发 completed（事件 → downloaded 再 +1，转 seeder）
            announce(tracker, hashA, 15901, 0, "completed");
            // L 重复 left=0 常规 announce：不再累计
            announce(tracker, hashA, 15901, 0, null);
            // swarm B：单个 leecher
            announce(tracker, hashB, 15902, 500, "started");

            FileEntry a = scrape(tracker, hashA);
            assertEquals(2, a.complete, "S + 完成后的 L");
            assertEquals(0, a.incomplete);
            assertEquals(2, a.downloaded(), "首次 left=0 与 completed 各一次");

            FileEntry b = scrape(tracker, hashB);
            assertEquals(0, b.complete);
            assertEquals(1, b.incomplete);
            assertEquals(0, b.downloaded());

            // 未知 hash：全零条目（BEP 48 任务口径，而非缺省）
            FileEntry unknown = scrape(tracker, infoHash(3));
            assertEquals(0, unknown.complete);
            assertEquals(0, unknown.downloaded());
            assertEquals(0, unknown.incomplete);
        }
    }

    /** 多 info_hash 单次查询 + 无参数全量 scrape。 */
    @Test
    void scrapeSupportsMultipleHashesAndFullScrape() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            announce(tracker, infoHash(4), 15910, 0, "started");
            announce(tracker, infoHash(5), 15911, 100, "started");

            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(tracker.announceUrl().replace("/announce",
                            "/scrape?info_hash=" + percentEncode(infoHash(4))
                                    + "&info_hash=" + percentEncode(infoHash(5))))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            BDict files = files(Bencode.decode(response.body()));
            assertEquals(2, files.value().size());
            assertTrue(files.value().containsKey(new BString(infoHash(4))));
            assertTrue(files.value().containsKey(new BString(infoHash(5))));

            // 无参数：返回全部已知 swarm
            HttpResponse<byte[]> full = http.send(
                    HttpRequest.newBuilder(URI.create(tracker.announceUrl()
                            .replace("/announce", "/scrape"))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, full.statusCode());
            assertEquals(2, files(Bencode.decode(full.body())).value().size());
        }
    }

    // ---- helpers ----

    private record FileEntry(int complete, int downloaded, int incomplete) {
    }

    private FileEntry scrape(EmbeddedTracker tracker, byte[] infoHash) throws Exception {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(tracker.announceUrl().replace("/announce",
                        "/scrape?info_hash=" + percentEncode(infoHash)))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        BencodeValue entry = files(Bencode.decode(response.body())).value().get(new BString(infoHash));
        assertTrue(entry instanceof BDict, "每个 info-hash 必须有条目");
        BDict dict = (BDict) entry;
        return new FileEntry(intOf(dict, "complete"), intOf(dict, "downloaded"), intOf(dict, "incomplete"));
    }

    private static BDict files(BencodeValue decoded) {
        assertTrue(decoded instanceof BDict);
        BencodeValue files = ((BDict) decoded).get("files");
        assertTrue(files instanceof BDict);
        return (BDict) files;
    }

    private static int intOf(BDict dict, String key) {
        BencodeValue value = dict.get(key);
        return value instanceof BInteger i ? (int) i.value() : -1;
    }

    private void announce(EmbeddedTracker tracker, byte[] infoHash, int port, long left, String event)
            throws Exception {
        StringBuilder url = new StringBuilder(tracker.announceUrl());
        url.append("?info_hash=").append(percentEncode(infoHash));
        url.append("&peer_id=").append(percentEncode(new byte[20]));
        url.append("&port=").append(port).append("&left=").append(left);
        if (event != null) {
            url.append("&event=").append(event);
        }
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

    private static String percentEncode(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 3);
        for (byte b : raw) {
            out.append('%').append(String.format("%02X", b));
        }
        return out.toString();
    }
}
