package io.github.oatelauser.thunder.core.internal.tracker;

import io.github.oatelauser.thunder.core.internal.wire.CompactPeer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UDP Tracker 客户端（BEP 15）：connect（64 位 connection_id，60s 缓存）→ announce。
 * 16 字节精简报文；事务 ID 校验；指数退避重传（2 次重试）。
 * 一个实例服务多个 tracker 主机：connection_id 按 host:port 缓存。
 */
public final class UdpTrackerClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UdpTrackerClient.class);
    private static final long CONNECT_PROTOCOL_ID = 0x41727101980AL;
    private static final long CONNECTION_TTL_MILLIS = 60_000;
    private static final int MAX_ATTEMPTS = 3;

    private record Connection(long id, long acquiredMillis) {
    }

    private final DatagramSocket socket;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;

    public UdpTrackerClient() throws IOException {
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(5000);
    }

    /** 支持 udp://host:port/announce 形态；其他 scheme 返回 false 交回 HTTP 客户端。 */
    public static boolean supports(String url) {
        return url != null && url.startsWith("udp://");
    }

    public AnnounceResponse announce(String announceUrl, AnnounceRequest request) {
        InetSocketAddress address = parse(announceUrl);
        if (address == null) {
            throw new TrackerException("invalid udp tracker url: " + announceUrl);
        }
        try {
            long connectionId = connectionFor(address);
            return announceWith(address, connectionId, request);
        } catch (IOException e) {
            throw new TrackerException("udp tracker I/O failure: " + announceUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrackerException("udp tracker interrupted", e);
        }
    }

    private long connectionFor(InetSocketAddress address) throws IOException, InterruptedException {
        String key = address.getHostString() + ":" + address.getPort();
        Connection cached = connections.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.acquiredMillis() < CONNECTION_TTL_MILLIS) {
            return cached.id();
        }
        int transactionId = newTransactionId();
        ByteBuffer out = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(CONNECT_PROTOCOL_ID)
            .putInt(0) // action: connect
            .putInt(transactionId);
        ByteBuffer response = exchange(address, out.array(), transactionId, 0);
        long id = response.getLong(8);
        connections.put(key, new Connection(id, now));
        return id;
    }

    private AnnounceResponse announceWith(InetSocketAddress address, long connectionId,
                                          AnnounceRequest request)
        throws IOException, InterruptedException {
        int transactionId = newTransactionId();
        ByteBuffer out = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN)
            .putLong(connectionId)
            .putInt(1) // action: announce
            .putInt(transactionId)
            .put(request.infoHash())
            .put(request.peerId())
            .putLong(request.downloaded())
            .putLong(request.uploaded())
            .putLong(request.left())
            .putInt(eventAction(request.event()))
            .putInt(request.port())
            .putInt(request.numwant()); // key/extensions 略（BEP 15 可选字段）
        ByteBuffer response = exchange(address, out.array(), transactionId, 1);
        // BEP 15 应答头：action/transaction_id 之外为 interval/leechers/seeders（注意与 HTTP
        // 的 complete/incomplete 顺序相反，leechers 在前），peer 紧凑表从偏移 20 起
        int interval = response.getInt(8);
        int leechers = response.getInt(12);
        int seeders = response.getInt(16);
        List<InetSocketAddress> peers = new ArrayList<>();
        for (int offset = 20; offset + 6 <= response.limit(); offset += 6) {
            peers.add(CompactPeer.decode6(response, offset));
        }
        return new AnnounceResponse(interval, seeders, leechers, peers, null);
    }

    /** 发送并等待同事务 ID 的响应；action 不符（含 error=3）抛异常。指数退避重试。 */
    private ByteBuffer exchange(InetSocketAddress address, byte[] wire, int transactionId,
                                int expectedAction) throws IOException, InterruptedException {
        long backoff = 500;
        IOException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (closed) {
                throw new IOException("client closed");
            }
            try {
                socket.send(new DatagramPacket(wire, wire.length, address.getAddress(),
                    address.getPort()));
                byte[] buffer = new byte[2048];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                ByteBuffer response = ByteBuffer.wrap(
                    Arrays.copyOf(packet.getData(), packet.getLength()))
                    .order(ByteOrder.BIG_ENDIAN);
                if (response.remaining() < 8 || response.getInt(4) != transactionId) {
                    continue; // 杂音/迟到旧事务：静默重试
                }
                int action = response.getInt(0);
                if (action == 3) {
                    // error：读消息文本
                    byte[] message = new byte[Math.max(0, response.remaining() - 8)];
                    response.position(8);
                    response.get(message);
                    throw new TrackerException("udp tracker error: "
                        + new String(message, StandardCharsets.UTF_8));
                }
                if (action != expectedAction) {
                    continue;
                }
                return response;
            } catch (SocketTimeoutException e) {
                last = e;
            }
            Thread.sleep(backoff);
            backoff *= 2;
        }
        throw last != null ? last : new IOException("udp exchange failed");
    }

    private static int eventAction(TrackerEvent event) {
        return switch (event) {
            case STARTED -> 2;
            case COMPLETED -> 1;
            case STOPPED -> 3;
            case NONE -> 0;
        };
    }

    private int newTransactionId() {
        return ThreadLocalRandom.current().nextInt();
    }

    private static InetSocketAddress parse(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 6969;
            if (host == null) {
                return null;
            }
            InetAddress resolved = InetAddress.getByName(host); // 阻塞 DNS；调用方在虚拟线程上
            return new InetSocketAddress(resolved, port);
        } catch (RuntimeException | UnknownHostException e) {
            return null;
        }
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
    }
}
