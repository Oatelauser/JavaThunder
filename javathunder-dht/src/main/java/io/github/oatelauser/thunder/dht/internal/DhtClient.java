package io.github.oatelauser.thunder.dht.internal;

import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Builder;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.NodeId;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Parsed;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.PeerAddr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DHT 客户端（BEP 5）：单 UDP socket + 接收线程 + 事务表。
 *
 * <p>核心能力：迭代式 find_node（自举与路由表填充）与 get_peers/announce_peer
 * （按 info-hash 找下载对端并宣告自己）。Kademlia alpha=3 并发度，K=8 桶宽。
 * bootstrap 节点可配置（公网默认 router.bittorrent.com 等；内网可自建并显式注入）。
 */
public final class DhtClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DhtClient.class);

    private static final int ALPHA = 3;
    private static final int QUERY_TIMEOUT_MILLIS = 2000;
    public static final List<String> DEFAULT_BOOTSTRAP = List.of("router.bittorrent.com:6881",
            "dht.transmissionbt.com:6881", "router.utorrent.com:6881");

    private final NodeId selfId;
    private final RoutingTable table;
    private final DatagramSocket socket;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /**
     * 待处理事务：事务ID → future。
     */
    private final ConcurrentHashMap<String, CompletableFuture<Parsed>> transactions = new ConcurrentHashMap<>();

    public DhtClient(int port) throws IOException {
        this.selfId = randomId();
        this.table = new RoutingTable(selfId);
        this.socket = new DatagramSocket(port);
        this.socket.setSoTimeout(250);
        Thread.ofVirtual().name("THUNDER-DHT").start(this::receiveLoop);
    }

    public static NodeId randomId() {
        byte[] id = new byte[20];
        new SecureRandom().nextBytes(id);
        return new NodeId(id);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public int knownNodes() {
        return table.size();
    }

    /**
     * 自举：向种子节点发起 find_node(self)，随后若干轮迭代填充路由表。
     */
    public void bootstrap(List<String> bootstrapNodes) {
        for (String spec : bootstrapNodes) {
            InetSocketAddress address = parse(spec);
            if (address == null) {
                continue;
            }
            sendQuery(address, Builder.query(newTransactionId(), "find_node")
                    .arg("target", new io.github.oatelauser.thunder.core.internal.bencode.BString(selfId.bytes()))
                    .id(selfId));
        }
        // 迭代收敛：几轮 nearest 查询填充
        for (int round = 0; round < 3 && table.size() < 64; round++) {
            iterativeFindNode(selfId);
            sleepMillis(100);
        }
        log.info("dht bootstrapped: {} nodes known", table.size());
    }

    /**
     * 按 info-hash 查找持有该种子的对端（迭代 get_peers），聚合 values 与 token 持有者。
     */
    public CompletableFuture<List<InetSocketAddress>> getPeers(byte[] infoHash) {
        NodeId target = new NodeId(infoHash);
        return CompletableFuture.supplyAsync(() -> {
            List<InetSocketAddress> peers = new ArrayList<>();
            Set<String> queried = new HashSet<>();
            Set<String> announced = new HashSet<>();
            List<RoutingTable.Entry> frontier = table.nearest(target, ALPHA * 2);
            int rounds = 0;
            while (!frontier.isEmpty() && rounds < 16) {
                rounds++;
                List<RoutingTable.Entry> batch = frontier.subList(0, Math.min(ALPHA, frontier.size()));
                frontier = new ArrayList<>(frontier.subList(Math.min(ALPHA, frontier.size()), frontier.size()));
                for (RoutingTable.Entry entry : batch) {
                    if (!queried.add(entry.id().hex())) {
                        continue;
                    }
                    InetSocketAddress address = new InetSocketAddress(entry.host(), entry.port());
                    Parsed response = roundTrip(address, Builder.query(newTransactionId(), "get_peers")
                            .arg("info_hash", new io.github.oatelauser.thunder.core.internal.bencode.BString(infoHash))
                            .id(selfId));
                    if (response == null) {
                        continue;
                    }
                    for (PeerAddr peer : response.values()) {
                        peers.add(new InetSocketAddress(peer.host(), peer.port()));
                    }
                    byte[] token = response.token();
                    if (token != null && announced.add(entry.id().hex())) {
                        // announce_peer：向给出 token 的节点宣告我们持有该 info-hash
                        roundTrip(address, Builder.query(newTransactionId(), "announce_peer")
                                .arg("info_hash", new io.github.oatelauser.thunder.core.internal.bencode.BString(infoHash))
                                .arg("port", new io.github.oatelauser.thunder.core.internal.bencode.BInteger(port()))
                                .arg("token", new io.github.oatelauser.thunder.core.internal.bencode.BString(token))
                                .id(selfId));
                    }
                    for (PeerAddr closer : response.nodes()) {
                        if (closer.nodeId() == null) {
                            continue;
                        }
                        NodeId closerId = new NodeId(closer.nodeId());
                        table.offer(closerId, closer.host(), closer.port());
                        frontier.add(new RoutingTable.Entry(closerId, closer.host(), closer.port()));
                    }
                }
                frontier.sort((a, b) -> RoutingTable.compareBytes(
                        a.id().distanceTo(target), b.id().distanceTo(target)));
                if (frontier.size() > 32) {
                    frontier = new ArrayList<>(frontier.subList(0, 32));
                }
                if (peers.size() >= 50) {
                    break;
                }
            }
            return peers;
        }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 通用迭代 find_node：返回过程中发现的全部节点（也用于路由表保养）。
     */
    List<RoutingTable.Entry> iterativeFindNode(NodeId target) {
        Set<String> queried = new HashSet<>();
        List<RoutingTable.Entry> found = new ArrayList<>();
        List<RoutingTable.Entry> frontier = table.nearest(target, ALPHA * 2);
        int rounds = 0;
        while (!frontier.isEmpty() && rounds < 8) {
            rounds++;
            List<RoutingTable.Entry> batch = new ArrayList<>(
                    frontier.subList(0, Math.min(ALPHA, frontier.size())));
            frontier = new ArrayList<>(frontier.subList(Math.min(ALPHA, frontier.size()), frontier.size()));
            for (RoutingTable.Entry entry : batch) {
                if (!queried.add(entry.id().hex())) {
                    continue;
                }
                Parsed response = roundTrip(new InetSocketAddress(entry.host(), entry.port()),
                        Builder.query(newTransactionId(), "find_node")
                                .arg("target", new io.github.oatelauser.thunder.core.internal.bencode.BString(target.bytes()))
                                .id(selfId));
                if (response == null) {
                    continue;
                }
                for (PeerAddr closer : response.nodes()) {
                    if (closer.nodeId() == null) {
                        continue;
                    }
                    NodeId closerId = new NodeId(closer.nodeId());
                    table.offer(closerId, closer.host(), closer.port());
                    found.add(new RoutingTable.Entry(closerId, closer.host(), closer.port()));
                    frontier.add(new RoutingTable.Entry(closerId, closer.host(), closer.port()));
                }
            }
            frontier.sort((a, b) -> RoutingTable.compareBytes(
                    a.id().distanceTo(target), b.id().distanceTo(target)));
            if (frontier.size() > 32) {
                frontier = new ArrayList<>(frontier.subList(0, 32));
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- transport

    private Parsed roundTrip(InetSocketAddress address, Builder query) {
        byte[] transactionId = null;
        // Builder 已内嵌事务 ID；为超时配对重新提取——简化：发送后等待（阻塞版查询）
        CompletableFuture<Parsed> future = new CompletableFuture<>();
        try {
            byte[] wire = query.encode();
            Parsed preview = KrpcMessage.parse(wire);
            transactionId = preview.transactionId();
            transactions.put(key(transactionId), future);
            socket.send(new DatagramPacket(wire, wire.length, address.getAddress(), address.getPort()));
            return future.get(QUERY_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException | IOException | ExecutionException e) {
            return null;
        } finally {
            if (transactionId != null) {
                transactions.remove(key(transactionId));
            }
        }
    }

    private void sendQuery(InetSocketAddress address, Builder query) {
        try {
            byte[] wire = query.encode();
            // 发后不管（bootstrap 探测）：响应由接收线程进路由表
            socket.send(new DatagramPacket(wire, wire.length, address.getAddress(), address.getPort()));
        } catch (IOException e) {
            log.debug("dht send to {} failed: {}", address, e.toString());
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!closed.get()) {
            try {
                socket.receive(packet);
                handlePacket(packet);
            } catch (SocketTimeoutException ignored) {
                // 周期唤醒检查 closed
            } catch (IOException e) {
                if (closed.get()) {
                    return;
                }
                // Windows 上向死端口发送后的 ICMP 不可达会让下一次 receive 抛
                // SocketException(Connection reset)；杂音/瞬时错误不应终止接收线程
                log.debug("dht receive failed (continuing): {}", e.toString());
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
        Parsed message;
        try {
            message = KrpcMessage.parse(data);
        } catch (IllegalArgumentException e) {
            return; // 非 KRPC 流量（同端口杂音），忽略
        }
        if (!"r".equals(message.type()) && !"e".equals(message.type())) {
            return; // 我们不响应他人查询（纯客户端模式；阶段后续可加服务侧）
        }
        // 记录响应者
        try {
            KrpcMessage.NodeId responder = message.nodeId();
            table.offer(responder, packet.getAddress().getHostAddress(), packet.getPort());
        } catch (IllegalArgumentException ignored) {
            // 无 id 的响应：仍可能携带数据，事务配对后照常完成
        }
        CompletableFuture<Parsed> future = transactions.remove(key(message.transactionId()));
        if (future != null) {
            future.complete(message);
        }
    }

    private byte[] newTransactionId() {
        byte[] id = new byte[2];
        random.nextBytes(id);
        return id;
    }

    private static String key(byte[] transactionId) {
        return java.util.HexFormat.of().formatHex(transactionId);
    }

    private static InetSocketAddress parse(String spec) {
        try {
            int colon = spec.lastIndexOf(':');
            InetSocketAddress address = new InetSocketAddress(spec.substring(0, colon),
                    Integer.parseInt(spec.substring(colon + 1)));
            // 未解析（DNS 失败/断网）时返回 null 跳过该节点：DatagramPacket 不接受 null 地址，
            // 否则 sendQuery 会以未捕获的 RuntimeException 中断整个 bootstrap
            return address.isUnresolved() ? null : address;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            socket.close();
        }
    }
}
