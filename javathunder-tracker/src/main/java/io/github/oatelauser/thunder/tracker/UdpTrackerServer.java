package io.github.oatelauser.thunder.tracker;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UDP announce（BEP 15 服务端）：单 socket 收发循环 + 报文编解码。
 * connect 无状态——每次发回新生成的 connection_id，announce 不校验之。
 * announce 请求按 BEP 15 标准布局解析（left@64、event@72、num_want@92、
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

    private static final Logger logger = LoggerFactory.getLogger(UdpTrackerServer.class);
    private final DatagramSocket socket;
    private final SwarmRegistry registry;
    private final TrackerMetrics metrics;
    private final int announceIntervalSeconds;

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
                ByteBuffer out = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                        .putInt(ACTION_CONNECT)
                        .putInt(transactionId)
                        .putLong(ThreadLocalRandom.current().nextLong());
                reply(packet, out.array());
            }
            case ACTION_ANNOUNCE -> handleAnnounce(in, packet, transactionId);
            default -> {
                // 未知 action：静默丢弃（不回包防放大）
            }
        }
    }

    private void handleAnnounce(ByteBuffer in, DatagramPacket packet, int transactionId) {
        if (in.remaining() < ANNOUNCE_REQUEST_BYTES) {
            return; // 布局不完整：忽略
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
}
