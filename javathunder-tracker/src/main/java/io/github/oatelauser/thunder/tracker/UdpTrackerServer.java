package io.github.oatelauser.thunder.tracker;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UDP announce（BEP 15 服务端）：单 socket 收发循环 + 报文编解码。
 * connect 无状态——每次发回新生成的 connection_id，announce 不校验之。
 * announce 请求布局镜像 core 的 UdpTrackerClient（port 为 int@84、numwant@88，
 * 与标准 BEP 15 的 ip/key 字段位置差异已在注释中标明）。畸形/未知 action
 * 静默丢弃（防放大，BEP 15 安全建议）；事务 ID 原样回带。
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
     * core UdpTrackerClient 布局的 announce 请求最小长度（port 84..88、numwant 88..92）。
     */
    private static final int ANNOUNCE_REQUEST_BYTES = 92;

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
                return; // socket 关闭或不可恢复：退出循环
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
        long left = in.position(72).getLong();
        int event = in.getInt(80);
        int port = in.getInt(84) & 0xFFFF; // core UdpTrackerClient 布局：port 为 int
        int numwant = in.getInt(88);
        String denied = registry.denyReason(infoHash);
        if (denied != null) {
            error(packet, transactionId, denied);
            return;
        }
        metrics.udpAnnounce();
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
