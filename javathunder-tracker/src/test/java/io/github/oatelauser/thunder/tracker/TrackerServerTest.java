package io.github.oatelauser.thunder.tracker;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
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
 * TrackerServer 生产外观：0.0.0.0 绑定（回环可达）、固定端口、可配置 announce
 * 间隔透传到响应、stats 委托、close 释放端口。
 */
class TrackerServerTest {

    @Test
    void servesOnFixedPortWithConfiguredInterval() throws Exception {
        int port;
        try (TrackerServer server = TrackerServer.start(0, 7)) {
            port = server.port();
            assertTrue(port > 0);

            // 0.0.0.0 绑定：announceUrl（回环形态）必须可达
            HttpClient http = HttpClient.newHttpClient();
            byte[] infoHash = new byte[20];
            new Random(42).nextBytes(infoHash);
            StringBuilder url = new StringBuilder(server.announceUrl());
            url.append("?info_hash=");
            for (byte b : infoHash) {
                url.append('%').append(String.format("%02X", b));
            }
            url.append("&peer_id=-javathunder-tracker-test-xx&port=6881&left=0&compact=1&event=started");
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(url.toString())).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            BencodeValue decoded = Bencode.decode(response.body());
            assertTrue(decoded instanceof BDict);
            assertEquals(7, ((BInteger) ((BDict) decoded).get("interval")).value(),
                    "configured announce interval must be served");

            assertEquals(1, server.stats().size());
            assertEquals(1, server.stats().values().iterator().next().seeders());
        }
        // close 后同端口可重启（固定端口语义）
        try (TrackerServer restarted = TrackerServer.start(port, 1800)) {
            assertEquals(port, restarted.port());
        }
    }
}
