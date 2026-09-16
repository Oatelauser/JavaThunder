package io.github.oatelauser.thunder.dht.internal;

import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Builder;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.NodeId;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Parsed;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.PeerAddr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * DHT 客户端（BEP 5）：迭代查找策略与自举编排。
 *
 * <p>核心能力：迭代式 find_node（自举与路由表填充）与 get_peers/announce_peer
 * （按 info-hash 找下载对端并宣告自己）。Kademlia alpha=3 并发度（{@link Frontier}），
 * K=8 桶宽。UDP 收发与事务配对在 {@link KrpcRpc}（构造时注入响应者观察回调把
 * 响应者写进路由表）。bootstrap 节点可配置（公网默认 router.bittorrent.com 等；
 * 内网可自建并显式注入）。
 */
public final class DhtClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DhtClient.class);

    public static final List<String> DEFAULT_BOOTSTRAP = List.of("router.bittorrent.com:6881",
            "dht.transmissionbt.com:6881", "router.utorrent.com:6881");

    private final NodeId selfId;
    private final RoutingTable table;
    private final KrpcRpc rpc;

    public DhtClient(int port) throws IOException {
        this.selfId = randomId();
        this.table = new RoutingTable(selfId);
        this.rpc = new KrpcRpc(port, this::recordResponder);
    }

    /** 响应者观察：写进路由表；无 id 的响应跳过（仍会被 KrpcRpc 做事务配对）。 */
    private void recordResponder(InetSocketAddress source, Parsed message) {
        try {
            table.offer(message.nodeId(), source.getAddress().getHostAddress(), source.getPort());
        } catch (IllegalArgumentException ignored) {
            // 无 id 的响应：仍可能携带数据，事务配对后照常完成
        }
    }

    public static NodeId randomId() {
        byte[] id = new byte[20];
        new SecureRandom().nextBytes(id);
        return new NodeId(id);
    }

    public int port() {
        return rpc.port();
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
            rpc.sendQuery(address, Builder.query(rpc.newTransactionId(), "find_node")
                    .arg("target", new BString(selfId.bytes()))
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
        CompletableFuture<List<InetSocketAddress>> result = new CompletableFuture<>();
        Thread.ofVirtual().name("javathunder-dht-lookup").start(() -> {
            try {
                result.complete(lookupPeers(target));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    /** 迭代 get_peers 主体：每轮向 alpha 个最近节点查询，聚合 values 中的 peer 地址；拿到 token 的节点随即 announce_peer。 */
    private List<InetSocketAddress> lookupPeers(NodeId target) {
        List<InetSocketAddress> peers = new ArrayList<>();
        Set<String> announced = new HashSet<>();
        Frontier frontier = new Frontier(target, table.nearest(target, Frontier.ALPHA * 2));
        for (int round = 0; round < 16 && !frontier.isEmpty() && peers.size() < 50; round++) {
            for (RoutingTable.Entry entry : frontier.takeBatch()) {
                queryGetPeers(entry, target, peers, announced, frontier);
            }
            frontier.tighten();
        }
        return peers;
    }

    /** 向单节点发 get_peers：收集 values、向 token 持有者 announce_peer、并入更近节点。 */
    private void queryGetPeers(RoutingTable.Entry entry, NodeId target,
            List<InetSocketAddress> peers, Set<String> announced, Frontier frontier) {
        InetSocketAddress address = new InetSocketAddress(entry.host(), entry.port());
        Parsed response = rpc.roundTrip(address, Builder.query(rpc.newTransactionId(), "get_peers")
                .arg("info_hash", new BString(target.bytes()))
                .id(selfId));
        if (response == null) {
            return;
        }
        for (PeerAddr peer : response.values()) {
            peers.add(new InetSocketAddress(peer.host(), peer.port()));
        }
        byte[] token = response.token();
        if (token != null && announced.add(entry.id().hex())) {
            // announce_peer：向给出 token 的节点宣告我们持有该 info-hash
            rpc.roundTrip(address, Builder.query(rpc.newTransactionId(), "announce_peer")
                    .arg("info_hash", new BString(target.bytes()))
                    .arg("port", new BInteger(port()))
                    .arg("token", new BString(token))
                    .id(selfId));
        }
        addCloserNodes(response, frontier);
    }

    /** 把响应 nodes 里的更近节点写进路由表并入 frontier（get_peers 路径）。 */
    private void addCloserNodes(Parsed response, Frontier frontier) {
        for (PeerAddr closer : response.nodes()) {
            if (closer.nodeId() == null) {
                continue;
            }
            NodeId closerId = new NodeId(closer.nodeId());
            table.offer(closerId, closer.host(), closer.port());
            frontier.offer(new RoutingTable.Entry(closerId, closer.host(), closer.port()));
        }
    }

    /**
     * 通用迭代 find_node：返回过程中发现的全部节点（也用于路由表保养）。
     */
    List<RoutingTable.Entry> iterativeFindNode(NodeId target) {
        List<RoutingTable.Entry> found = new ArrayList<>();
        Frontier frontier = new Frontier(target, table.nearest(target, Frontier.ALPHA * 2));
        for (int round = 0; round < 8 && !frontier.isEmpty(); round++) {
            for (RoutingTable.Entry entry : frontier.takeBatch()) {
                queryFindNode(entry, target, found, frontier);
            }
            frontier.tighten();
        }
        return found;
    }

    /** 向单节点发 find_node：更近节点写进路由表、结果集与 frontier。 */
    private void queryFindNode(RoutingTable.Entry entry, NodeId target,
            List<RoutingTable.Entry> found, Frontier frontier) {
        Parsed response = rpc.roundTrip(new InetSocketAddress(entry.host(), entry.port()),
                Builder.query(rpc.newTransactionId(), "find_node")
                        .arg("target", new BString(target.bytes()))
                        .id(selfId));
        if (response == null) {
            return;
        }
        for (PeerAddr closer : response.nodes()) {
            if (closer.nodeId() == null) {
                continue;
            }
            NodeId closerId = new NodeId(closer.nodeId());
            table.offer(closerId, closer.host(), closer.port());
            found.add(new RoutingTable.Entry(closerId, closer.host(), closer.port()));
            frontier.offer(new RoutingTable.Entry(closerId, closer.host(), closer.port()));
        }
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
        rpc.close();
    }
}
