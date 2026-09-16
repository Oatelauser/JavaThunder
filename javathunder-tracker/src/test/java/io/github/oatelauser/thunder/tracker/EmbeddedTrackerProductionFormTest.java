package io.github.oatelauser.thunder.tracker;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EmbeddedTracker 生产形态直测：通配地址绑定（0.0.0.0，回环可达）、固定端口、
 * 可配置 announce 间隔透传到响应、stats 可观测、close 释放端口（同端口可重启）。
 * 原 TrackerServer 转发壳（0.4.0 移除）的覆盖由此承接。
 */
class EmbeddedTrackerProductionFormTest {

    @Test
    void servesOnFixedPortWithConfiguredInterval() throws Exception {
        int port;
        try (EmbeddedTracker server = EmbeddedTracker.start(wildcardAddress(), 0, 7)) {
            port = server.port();
            assertTrue(port > 0);

            // 通配绑定：announceUrl（回环形态）必须可达
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
        try (EmbeddedTracker restarted = EmbeddedTracker.start(wildcardAddress(), port, 1800)) {
            assertEquals(port, restarted.port());
        }
    }

    private static InetAddress wildcardAddress() {
        try {
            return InetAddress.getByAddress(new byte[]{0, 0, 0, 0});
        } catch (Exception e) {
            throw new AssertionError("unreachable: literal 0.0.0.0", e);
        }
    }
}
