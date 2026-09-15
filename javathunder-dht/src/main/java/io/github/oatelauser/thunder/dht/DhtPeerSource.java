package io.github.oatelauser.thunder.dht;

import io.github.oatelauser.thunder.dht.internal.DhtClient;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * DHT 对等发现源（BEP 5，公共入口）。可选模块：引入本依赖并在 TorrentClient
 * builder 注入后，磁力链接即可摆脱 tracker（内网可自建 bootstrap 节点列表注入）。
 *
 * <pre>
 * try (DhtPeerSource dht = DhtPeerSource.start()) {
 *     dht.bootstrap();  // 或 bootstrap(List.of("dht.lan:6881"))
 *     List&lt;InetSocketAddress&gt; peers = dht.getPeers(infoHash).get();
 * }
 * </pre>
 */
public final class DhtPeerSource implements AutoCloseable {

    private final DhtClient client;

    private DhtPeerSource(DhtClient client) {
        this.client = client;
    }

    public static DhtPeerSource start() throws IOException {
        return start(0);
    }

    /** @param port UDP 端口；0 由系统分配。 */
    public static DhtPeerSource start(int port) throws IOException {
        return new DhtPeerSource(new DhtClient(port));
    }

    /** 公网默认 bootstrap 节点（router.bittorrent.com 等）。 */
    public void bootstrap() {
        client.bootstrap(DhtClient.DEFAULT_BOOTSTRAP);
    }

    /** 自定义 bootstrap（内网自建节点）。 */
    public void bootstrap(List<String> nodes) {
        client.bootstrap(nodes);
    }

    /** 按 info-hash 迭代查找持有者（get_peers + announce_peer）。 */
    public CompletableFuture<List<InetSocketAddress>> getPeers(byte[] infoHash) {
        return client.getPeers(infoHash);
    }

    /** 已知的 DHT 节点数（健康度观测）。 */
    public int knownNodes() {
        return client.knownNodes();
    }

    @Override
    public void close() {
        client.close();
    }
}
