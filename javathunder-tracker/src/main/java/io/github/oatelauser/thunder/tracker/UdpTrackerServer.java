package io.github.oatelauser.thunder.tracker;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UDP announce（BEP 15 服务端）：单 socket 收发循环 + 报文编解码。
 * connect 有状态——发回的 connection_id 绑定来源地址（60s TTL，惰性过期），
 * announce 校验该绑定：id 未知/过期/来源不符回 error(action=3) 且不注册 peer
 * （BEP 15 防伪造源地址），命中则滑动续期。
 * announce 请求按 BEP 15 标准布局解析（left@64、event@80、num_want@92、
 * port@96 两字节）——与第三方客户端（libtorrent 等）及 core 客户端一致。
 * 畸形/未知 action 静默丢弃（防放大，BEP 15 安全建议）；事务 ID 原样回带。
 */
final class UdpTrackerServer {

    static final long CONNECT_PROTOCOL_ID = 0x41727101980AL;
    static final int ACTION_CONNECT = 0;
    static final int ACTION_ANNOUNCE = 1;
    static final int ACTION_ERROR = 3;
    /**
     * BEP 15 event 数值：1=completed，3=stopped。
     */
    private static final int EVENT_COMPLETED = 1;
    private static final int EVENT_STOPPED = 3;
    /**
     * BEP 15 标准布局的 announce 请求长度（含 port@96 两字节，共 98）。
     */
    private static final int ANNOUNCE_REQUEST_BYTES = 98;
    /**
     * connection_id 绑定的 TTL，与 core 客户端 UdpTrackerClient.CONNECTION_TTL_MILLIS
     * 同为 60s——两侧同值存在时钟边界竞态：客户端 59.9s 复用缓存时服务端可能刚好过期，
     * 服务端此时回 error(action=3)，由客户端丢弃缓存重连自愈。
     */
    private static final long CONNECTION_TTL_MILLIS = 60_000;

    private static final Logger logger = LoggerFactory.getLogger(UdpTrackerServer.class);
    private final DatagramSocket socket;
    private final SwarmRegistry registry;
    private final TrackerMetrics metrics;
    private final int announceIntervalSeconds;
    /**
     * connect 发出的 connection_id → 来源绑定。serve() 单线程收包串行读写，普通
     * HashMap 即可；容量由 TTL + 惰性清理（见 {@link #expireStaleConnections}）约束。
     */
    private final Map<Long, ConnectionBinding> connections = new HashMap<>();

    UdpTrackerServer(DatagramSocket socket, SwarmRegistry registry, TrackerMetrics metrics,
            int announceIntervalSeconds) {
        this.socket = socket;
        this.registry = registry;
        this.metrics = metrics;
        this.announceIntervalSeconds = announceIntervalSeconds;
    }

    /**
     * 启动接收循环（虚拟线程）；socket 关闭时退出。
     */
    void serveAsync() {
        Thread.ofVirtual().name("tracker-udp-" + socket.getLocalPort()).start(this::serve);
    }

    int port() {
        return socket.getLocalPort();
    }

    void close() {
        socket.close(); // 阻塞在 receive 的监听线程随之退出
    }

    private void serve() {
        byte[] buffer = new byte[2048];
        while (!socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(ByteBuffer.wrap(buffer, 0, packet.getLength()).order(ByteOrder.BIG_ENDIAN),
                        packet);
            } catch (IOException e) {
                if (socket.isClosed()) {
                    return; // 正常关停
                }
                // Windows 上向已消失对端回包后，ICMP 端口不可达会让下一次 receive 抛
                // SocketException——单次 IO 异常不得静默杀死整个 UDP 服务线程
                // （对照 dht KrpcRpc.receiveLoop 的同款处理）
                logger.debug("udp tracker receive failed; continuing: {}", e.toString());
            }
        }
    }

    private void handlePacket(ByteBuffer in, DatagramPacket packet) {
        if (in.remaining() < 16) {
            return; // 头部不完整：忽略
        }
        long connectionId = in.getLong(0);
        int action = in.getInt(8);
        int transactionId = in.getInt(12);
        switch (action) {
            case ACTION_CONNECT -> {
                if (connectionId != CONNECT_PROTOCOL_ID || in.remaining() != 16) {
                    return; // 非法 connect：忽略
                }
                handleConnect(packet, transactionId);
            }
            case ACTION_ANNOUNCE -> handleAnnounce(in, packet, connectionId, transactionId);
            default -> {
                // 未知 action：静默丢弃（不回包防放大）
            }
        }
    }

    private void handleConnect(DatagramPacket packet, int transactionId) {
        long now = System.currentTimeMillis();
        expireStaleConnections(now);
        long connectionId = ThreadLocalRandom.current().nextLong();
        connections.put(connectionId, new ConnectionBinding(
                packet.getAddress(), packet.getPort(), now + CONNECTION_TTL_MILLIS));
        ByteBuffer out = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putInt(ACTION_CONNECT)
                .putInt(transactionId)
                .putLong(connectionId);
        reply(packet, out.array());
    }

    private void handleAnnounce(ByteBuffer in, DatagramPacket packet, long connectionId, int transactionId) {
        if (in.remaining() < ANNOUNCE_REQUEST_BYTES) {
            return; // 布局不完整：忽略
        }
        String bindingFailure = connectionFailureReason(packet, connectionId);
        if (bindingFailure != null) {
            error(packet, transactionId, bindingFailure);
            return;
        }
        byte[] infoHash = new byte[20];
        in.position(16).get(infoHash);
        // BEP 15 标准偏移：left@64、event@80、ip@84/key@88（不消费）、num_want@92、
        // port@96（2 字节大端无符号）。曾镜像 core 客户端的私有布局（left@72/port 为
        // int@84）——两侧已一并改回标准，第三方客户端（libtorrent 等）可直连
        long left = in.getLong(64);
        int event = in.getInt(80);
        int numwant = in.getInt(92);
        int port = in.getShort(96) & 0xFFFF;
        String denied = registry.denyReason(infoHash);
        if (denied != null) {
            error(packet, transactionId, denied);
            return;
        }
        metrics.udpAnnounce();
        // numwant ≤ 0 一律视为不限量：core 客户端缺省发 -1；0 在 BEP 3 语义里是"不要 peer"，
        // 此处按不限量处理（镜像 core 客户端行为，见 UdpClientInteropTest）
        SwarmRegistry.SwarmView view = registry.apply(new SwarmRegistry.Announce(
                infoHash,
                new InetSocketAddress(packet.getAddress().getHostAddress(), port),
                left == 0, event == EVENT_STOPPED, event == EVENT_COMPLETED,
                numwant > 0 ? numwant : -1));
        // BEP 15 应答：interval/leechers/seeders（与 HTTP 的 complete/incomplete 顺序相反）
        ByteBuffer out = ByteBuffer.allocate(20 + view.peersCompact().length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(ACTION_ANNOUNCE)
                .putInt(transactionId)
                .putInt(announceIntervalSeconds)
                .putInt(view.leechers())
                .putInt(view.seeders())
                .put(view.peersCompact());
        reply(packet, out.array());
    }

    /**
     * announce 的 connection_id 绑定校验（BEP 15：id 必须出自同一源地址的 connect，
     * 防第三方伪造源地址污染 peer 表）：未知/过期/来源不符返回简短 error 理由，
     * 不注册 peer；命中则滑动续期并返回 null。
     */
    private @Nullable String connectionFailureReason(DatagramPacket packet, long connectionId) {
        long now = System.currentTimeMillis();
        expireStaleConnections(now);
        ConnectionBinding binding = connections.get(connectionId);
        if (binding == null) {
            return "connection id stale";
        }
        if (!binding.matches(packet)) {
            return "connection id mismatch";
        }
        connections.put(connectionId, binding.renewed(now));
        return null;
    }

    /**
     * 惰性剔除过期绑定：刻意不设后台清理线程——无包到达时缓存不再增长，O(n) 扫描
     * 摊入每次 connect/announce 即可防无限膨胀。
     */
    private void expireStaleConnections(long now) {
        connections.values().removeIf(binding -> binding.isStale(now));
    }

    private void error(DatagramPacket packet, int transactionId, String message) {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(8 + text.length).order(ByteOrder.BIG_ENDIAN)
                .putInt(ACTION_ERROR)
                .putInt(transactionId)
                .put(text);
        reply(packet, out.array());
    }

    private void reply(DatagramPacket request, byte[] wire) {
        try {
            socket.send(new DatagramPacket(wire, wire.length, request.getAddress(), request.getPort()));
        } catch (IOException ignored) {
            // 对端消失/网络抖动：UDP 丢包即弃
        }
    }

    /**
     * connect 时刻的来源绑定：connection_id 仅对 connect 时的源地址有效（BEP 15），
     * TTL 内可重复 announce，命中即整体续期（不可变，续期 = 替换记录）。
     */
    private record ConnectionBinding(InetAddress source, int port, long expiresAtMillis) {

        boolean isStale(long now) {
            return now >= expiresAtMillis;
        }

        boolean matches(DatagramPacket packet) {
            return port == packet.getPort() && source.equals(packet.getAddress());
        }

        ConnectionBinding renewed(long now) {
            return new ConnectionBinding(source, port, now + CONNECTION_TTL_MILLIS);
        }
    }
}
