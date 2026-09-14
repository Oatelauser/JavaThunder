package io.github.oatelauser.thunder.core.internal.tracker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内嵌 HTTP 服务器充当假 Tracker：请求参数在服务器侧独立解码比对；
 * 响应用已通过测试的 Bencode 编码器构造（含 BEP 23 紧凑 Peer 表）。
 */
class TrackerClientTest {

    private HttpServer server;
    private String announceUrl;
    private final Map<String, String> lastQuery = new ConcurrentHashMap<>();
    private volatile byte[] nextBody = new byte[0];
    private volatile int nextStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/announce", this::handle);
        server.start();
        announceUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/announce";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastQuery.clear();
        lastQuery.putAll(parseQuery(exchange.getRequestURI().getRawQuery()));
        byte[] body = nextBody;
        exchange.sendResponseHeaders(nextStatus, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 服务器侧独立实现：拆 raw query，把 %XX 还原为原始字节。 */
    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new ConcurrentHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            params.put(percentDecode(pair.substring(0, eq)), percentDecode(pair.substring(eq + 1)));
        }
        return params;
    }

    private static String percentDecode(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '%') {
                out.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                out.append(s.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** info-hash 故意全用 >0x7F 的二进制字节：验证逐字节百分号编码不被字符集破坏。 */
    private static byte[] binaryInfoHash() {
        byte[] infoHash = new byte[20];
        for (int i = 0; i < 20; i++) {
            infoHash[i] = (byte) (0x80 + i);
        }
        return infoHash;
    }

    private static AnnounceRequest startedRequest() {
        return new AnnounceRequest(binaryInfoHash(),
            "-JT0001-unittest0001".getBytes(StandardCharsets.US_ASCII),
            6881, 100, 200, 300, TrackerEvent.STARTED, 50);
    }

    private static byte[] peersRaw(int... bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = (byte) bytes[i];
        }
        return out;
    }

    @Test
    void announcesWithSpecParametersAndParsesCompactPeers() {
        // BEP 23 紧凑表：127.0.0.1:8080 与 10.0.0.2:51463
        Map<BString, BencodeValue> body = new TreeMap<>(BString.UNSIGNED_ORDER);
        body.put(BString.of("interval"), new BInteger(900));
        body.put(BString.of("complete"), new BInteger(5));
        body.put(BString.of("incomplete"), new BInteger(2));
        body.put(BString.of("peers"), new BString(peersRaw(
            127, 0, 0, 1, 0x1F, (byte) 0x90,
            10, 0, 0, 2, (byte) 0xC9, 0x07)));
        nextBody = Bencode.encode(new BDict(body));

        AnnounceResponse response = new TrackerClient().announce(announceUrl, startedRequest());

        assertEquals(900, response.interval());
        assertEquals(5, response.seeders());
        assertEquals(2, response.leechers());
        assertEquals(List.of(
            new InetSocketAddress("127.0.0.1", 8080),
            new InetSocketAddress("10.0.0.2", 51463)), response.peers());
        assertNull(response.failureReason());

        // 服务器侧看到的请求参数（独立于客户端的编码实现）
        String infoHashBack = new String(binaryInfoHash(), StandardCharsets.ISO_8859_1);
        assertEquals(infoHashBack, lastQuery.get("info_hash"));
        assertEquals("-JT0001-unittest0001", lastQuery.get("peer_id"));
        assertEquals("6881", lastQuery.get("port"));
        assertEquals("100", lastQuery.get("uploaded"));
        assertEquals("200", lastQuery.get("downloaded"));
        assertEquals("300", lastQuery.get("left"));
        assertEquals("1", lastQuery.get("compact"));
        assertEquals("1", lastQuery.get("no_peer_id"));
        assertEquals("started", lastQuery.get("event"));
        assertEquals("50", lastQuery.get("numwant"));
    }

    @Test
    void periodicAnnounceOmitsEventParameter() {
        nextBody = Bencode.encode(new BDict(Map.of(BString.of("interval"), new BInteger(60))));
        AnnounceRequest req = new AnnounceRequest(binaryInfoHash(),
            "-JT0001-unittest0001".getBytes(StandardCharsets.US_ASCII),
            6881, 0, 0, 0, TrackerEvent.NONE, 50);

        new TrackerClient().announce(announceUrl, req);

        assertTrue(!lastQuery.containsKey("event"));
    }

    @Test
    void parsesDictionaryModelPeersAsFallback() {
        // BEP 3 字典模型：老式 tracker 忽略 compact 请求时的兜底
        Map<BString, BencodeValue> peer1 = new TreeMap<>(BString.UNSIGNED_ORDER);
        peer1.put(BString.of("ip"), BString.of("192.168.1.7"));
        peer1.put(BString.of("port"), new BInteger(7000));
        Map<BString, BencodeValue> body = new TreeMap<>(BString.UNSIGNED_ORDER);
        body.put(BString.of("interval"), new BInteger(60));
        body.put(BString.of("peers"), new BList(List.of(new BDict(peer1))));
        nextBody = Bencode.encode(new BDict(body));

        AnnounceResponse response = new TrackerClient().announce(announceUrl, startedRequest());

        assertEquals(List.of(new InetSocketAddress("192.168.1.7", 7000)), response.peers());
    }

    @Test
    void failureReasonIsReturnedNotThrown() {
        nextBody = Bencode.encode(new BDict(Map.of(
            BString.of("failure reason"), BString.of("torrent not registered"))));

        AnnounceResponse response = new TrackerClient().announce(announceUrl, startedRequest());

        assertNotNull(response.failureReason());
        assertEquals("torrent not registered", response.failureReason());
        assertEquals(List.of(), response.peers());
    }

    @Test
    void emptyPeersKeyIsLegal() {
        nextBody = Bencode.encode(new BDict(Map.of(
            BString.of("interval"), new BInteger(60),
            BString.of("peers"), new BString(new byte[0]))));

        assertEquals(List.of(), new TrackerClient().announce(announceUrl, startedRequest()).peers());
    }

    @Test
    void nonBencodeBodyFails() {
        nextBody = "plain text".getBytes(StandardCharsets.UTF_8);
        assertThrows(TrackerException.class,
            () -> new TrackerClient().announce(announceUrl, startedRequest()));
    }

    @Test
    void httpErrorStatusFails() {
        nextStatus = 503;
        nextBody = new byte[0];
        assertThrows(TrackerException.class,
            () -> new TrackerClient().announce(announceUrl, startedRequest()));
    }

    @Test
    void peerIdsFollowBep20Convention() {
        String first = new String(PeerIds.generate(), StandardCharsets.US_ASCII);
        String second = new String(PeerIds.generate(), StandardCharsets.US_ASCII);

        assertEquals(20, first.length());
        assertTrue(first.startsWith("-JT0001-"), "BEP 20 Azureus-style prefix: " + first);
        assertTrue(first.substring(8).matches("[A-Za-z0-9]{12}"));
        assertTrue(!first.equals(second));
    }
}
