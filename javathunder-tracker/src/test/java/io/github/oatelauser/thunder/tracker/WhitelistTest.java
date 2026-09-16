package io.github.oatelauser.thunder.tracker;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 白名单开/关两态：默认全放行；启用后非白名单 announce 收 failure reason
 * "torrent not registered" 且 swarm 不被创建；白名单内正常；关闭后恢复全放行。
 */
class WhitelistTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void whitelistRejectsUnregisteredTorrentsWhenEnabled() throws Exception {
        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            byte[] listed = infoHash(1);
            byte[] other = infoHash(2);

            // 默认：关闭，全放行
            assertFalse(tracker.whitelistEnabled());
            announce(tracker, other, 15400, 0);
            assertEquals(1, tracker.stats().get(hex(2)).total());

            // 启用：other 已不在名单 → 拒绝
            tracker.enableWhitelist(Arrays.asList(listed));
            assertTrue(tracker.whitelistEnabled());
            BDict denied = announce(tracker, other, 15400, 0);
            assertEquals("torrent not registered", ((BString) denied.get("failure reason")).text());

            // 名单内：正常 announce（即使此前从未出现过）
            BDict allowed = announce(tracker, listed, 15401, 0);
            assertTrue(allowed.get("failure reason") == null, "白名单内不得拒绝");
            assertEquals(1, tracker.stats().get(hex(1)).total());

            // 被拒的 announce 不得创建 swarm（hex(2) 已有的 swarm 来自启用前的合法 announce）
            tracker.enableWhitelist(Arrays.asList(listed)); // 替换语义
            announce(tracker, infoHash(3), 15402, 0);
            assertNull(tracker.stats().get(hex(3)), "被拒 swarm 不得残留");

            // 关闭：恢复全放行
            tracker.disableWhitelist();
            assertFalse(tracker.whitelistEnabled());
            BDict reopened = announce(tracker, infoHash(3), 15402, 0);
            assertNull(reopened.get("failure reason"));
            assertEquals(1, tracker.stats().get(hex(3)).total());
        }
    }

    // ---- helpers ----

    private BDict announce(EmbeddedTracker tracker, byte[] infoHash, int port, long left)
            throws Exception {
        StringBuilder url = new StringBuilder(tracker.announceUrl());
        url.append("?info_hash=").append(percentEncode(infoHash));
        url.append("&peer_id=").append(percentEncode(new byte[20]));
        url.append("&port=").append(port).append("&left=").append(left);
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(url.toString())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        BencodeValue decoded = Bencode.decode(response.body());
        assertTrue(decoded instanceof BDict);
        return (BDict) decoded;
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
