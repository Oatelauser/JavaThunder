package io.github.oatelauser.thunder.core.internal.tracker;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import io.github.oatelauser.thunder.core.internal.wire.CompactPeer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP Tracker 客户端（BEP 3 / 23）。线程安全：单实例可被多任务并发使用。
 */
public final class TrackerClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** P3C：tracker 应答缺 interval/给非正值时的保底重查间隔（秒）。 */
    private static final int FALLBACK_INTERVAL_SECONDS = 60;
    /** BEP 3/BEP 23 紧凑 peer 表项长度：4 字节 IPv4 + 2 字节大端端口。 */
    private static final int COMPACT_PEER_BYTES = 6;
    private static final String USER_AGENT = "JavaThunder/0.1";

    private final HttpClient http;

    public TrackerClient() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    TrackerClient(HttpClient http) {
        this.http = http;
    }

    public AnnounceResponse announce(String announceUrl, AnnounceRequest request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(buildUrl(announceUrl, request)))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        return parseResponse(sendForBody(announceUrl, httpRequest));
    }

    /**
     * 执行 announce GET 并取回应答体；非 2xx / 网络故障 / 中断统一转
     * {@link TrackerException}，由上层（tier 转移与退避）决策。
     */
    private byte[] sendForBody(String announceUrl, HttpRequest httpRequest) {
        try {
            HttpResponse<byte[]> response =
                    http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new TrackerException("tracker HTTP " + response.statusCode() + ": " + announceUrl);
            }
            return response.body();
        } catch (IOException e) {
            throw new TrackerException("tracker I/O failure: " + announceUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrackerException("tracker announce interrupted", e);
        }
    }

    static String buildUrl(String announceUrl, AnnounceRequest request) {
        StringBuilder url = new StringBuilder(announceUrl);
        url.append(announceUrl.contains("?") ? '&' : '?');
        url.append("info_hash=").append(QueryEncoding.encode(request.infoHash()));
        url.append("&peer_id=").append(QueryEncoding.encode(request.peerId()));
        url.append("&port=").append(request.port());
        url.append("&uploaded=").append(request.uploaded());
        url.append("&downloaded=").append(request.downloaded());
        url.append("&left=").append(request.left());
        // BEP 23：紧凑模式每个 peer 仅 6 字节，较字典模型省一个数量级带宽，
        // 主流 tracker 均支持；no_peer_id 在紧凑模式下冗余，仅为兼容旧应答。
        url.append("&compact=1&no_peer_id=1");
        if (request.event().wireValue() != null) {
            url.append("&event=").append(request.event().wireValue());
        }
        url.append("&numwant=").append(request.numwant());
        return url.toString();
    }

    private static AnnounceResponse parseResponse(byte[] body) {
        BencodeValue decoded;
        try {
            decoded = Bencode.decode(body);
        } catch (RuntimeException e) {
            throw new TrackerException("tracker response is not valid bencode", e);
        }
        if (!(decoded instanceof BDict dict)) {
            throw new TrackerException("tracker response must be a dict");
        }
        BencodeValue failure = dict.get("failure reason");
        if (failure instanceof BString reason) {
            return new AnnounceResponse(0, 0, 0, List.of(), reason.text());
        }
        int interval = intOf(dict.get("interval"), FALLBACK_INTERVAL_SECONDS);
        int seeders = intOf(dict.get("complete"), 0);
        int leechers = intOf(dict.get("incomplete"), 0);
        List<InetSocketAddress> peers = parsePeers(dict.get("peers"));
        return new AnnounceResponse(interval, seeders, leechers, peers, null);
    }

    private static int intOf(BencodeValue value, int fallback) {
        if (!(value instanceof BInteger i)) {
            return fallback;
        }
        long v = i.value();
        return (v >= 0 && v <= Integer.MAX_VALUE) ? (int) v : fallback;
    }

    private static List<InetSocketAddress> parsePeers(BencodeValue peers) {
        if (peers == null) {
            return List.of();
        }
        if (peers instanceof BList list) {
            List<InetSocketAddress> result = new ArrayList<>();
            for (BencodeValue entry : list.value()) {
                if (!(entry instanceof BDict peer)) {
                    throw new TrackerException("peers entry must be a dict");
                }
                if (!(peer.get("ip") instanceof BString ip) || !(peer.get("port") instanceof BInteger port)) {
                    throw new TrackerException("peers entry requires ip and port");
                }
                long portValue = port.value();
                if (portValue < 0 || portValue > 65535) {
                    throw new TrackerException("peers entry has invalid port: " + portValue);
                }
                result.add(new InetSocketAddress(ip.text(), (int) portValue));
            }
            return result;
        }
        if (peers instanceof BString compact) {
            byte[] data = compact.value();
            if (data.length % COMPACT_PEER_BYTES != 0) {
                throw new TrackerException("compact peers must be " + COMPACT_PEER_BYTES
                        + " bytes each, got " + data.length);
            }
            List<InetSocketAddress> result = new ArrayList<>(data.length / COMPACT_PEER_BYTES);
            for (int i = 0; i < data.length; i += COMPACT_PEER_BYTES) {
                result.add(CompactPeer.decode6(data, i));
            }
            return result;
        }
        throw new TrackerException("peers must be a byte string or a list");
    }
}
