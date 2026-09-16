package io.github.oatelauser.thunder.tracker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内嵌 HTTP Tracker（BEP 3/23）：内存 Peer 表，compact 响应，interval=2s。
 */
public final class EmbeddedTracker implements AutoCloseable {

    private final HttpServer server;
    private final ConcurrentMap<String, ConcurrentMap<InetSocketAddress, Boolean>> swarms =
            new ConcurrentHashMap<>();

    private EmbeddedTracker(HttpServer server) {
        this.server = server;
    }

    public static EmbeddedTracker start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        EmbeddedTracker tracker = new EmbeddedTracker(server);
        server.createContext("/announce", tracker::handle);
        server.start();
        return tracker;
    }

    public String announceUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/announce";
    }

    /**
     * 种子方直接注册（FakeSeeder 用，绕过 HTTP announce）。
     */
    public void register(byte[] infoHash, int port) {
        swarm(infoHash).put(new InetSocketAddress("127.0.0.1", port), Boolean.TRUE);
    }

    private ConcurrentMap<InetSocketAddress, Boolean> swarm(byte[] infoHash) {
        return swarms.computeIfAbsent(HexFormat.of().formatHex(infoHash), k -> new ConcurrentHashMap<>());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            Map<String, byte[]> params = Query.parse(exchange.getRequestURI().getRawQuery());
            byte[] infoHash = params.get("info_hash");
            byte[] portBytes = params.get("port");
            if (infoHash == null || infoHash.length != 20 || portBytes == null) {
                respond(exchange, failure("invalid announce"));
                return;
            }
            int port = Integer.parseInt(new String(portBytes, StandardCharsets.US_ASCII));
            String remoteIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            InetSocketAddress self = new InetSocketAddress(remoteIp, port);
            ConcurrentMap<InetSocketAddress, Boolean> swarm = swarm(infoHash);
            swarm.put(self, Boolean.TRUE);

            byte[] peers = compactPeers(swarm, self);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(ascii("d8:intervali2e8:completei1e10:incompletei"
                    + Math.max(0, swarm.size() - 1) + "e5:peers"));
            body.writeBytes(ascii(String.valueOf(peers.length)));
            body.write(':');
            body.writeBytes(peers);
            body.write('e');
            respond(exchange, body.toByteArray());
        } catch (RuntimeException e) {
            respond(exchange, failure("tracker error: " + e));
        }
    }

    private static byte[] compactPeers(ConcurrentMap<InetSocketAddress, Boolean> swarm, InetSocketAddress self) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (InetSocketAddress peer : swarm.keySet()) {
            if (peer.equals(self)) {
                continue;
            }
            byte[] address = peer.getAddress().getAddress();
            out.write(address, 0, 4);
            out.write(peer.getPort() >> 8);
            out.write(peer.getPort() & 0xFF);
        }
        return out.toByteArray();
    }

    private static byte[] failure(String reason) {
        return ascii("d14:failure reason" + reason.length() + ":" + reason + "e");
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * raw query 解析（%XX → 原始字节）。
     */
    private static final class Query {
        static Map<String, byte[]> parse(String rawQuery) {
            Map<String, byte[]> params = new ConcurrentHashMap<>();
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                params.put(new String(decode(pair.substring(0, eq)), StandardCharsets.UTF_8),
                        decode(pair.substring(eq + 1)));
            }
            return params;
        }

        static byte[] decode(String s) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < s.length(); ) {
                if (s.charAt(i) == '%') {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 3;
                } else {
                    out.write(s.charAt(i));
                    i++;
                }
            }
            return out.toByteArray();
        }
    }
}
