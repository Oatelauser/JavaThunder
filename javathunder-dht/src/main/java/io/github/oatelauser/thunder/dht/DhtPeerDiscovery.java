package io.github.oatelauser.thunder.dht;

import io.github.oatelauser.thunder.api.PeerDiscoverySource;
import io.github.oatelauser.thunder.dht.internal.DhtClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link PeerDiscoverySource} 的 DHT 实现：把引擎的 Peer 发现接到 BEP 5 网络。
 *
 * <pre>
 * TorrentClient.builder()
 *     .peerDiscovery(DhtPeerDiscovery.create())   // 公网默认自举
 *     .build();
 * // 内网：DhtPeerDiscovery.create(List.of("dht.lan:6881"))
 * </pre>
 */
public final class DhtPeerDiscovery implements PeerDiscoverySource {

    private final DhtClient client;

    private DhtPeerDiscovery(DhtClient client) {
        this.client = client;
    }

    /** 公网默认 bootstrap 节点。 */
    public static DhtPeerDiscovery create() throws IOException {
        return create(DhtClient.DEFAULT_BOOTSTRAP);
    }

    /** 自定义 bootstrap（内网自建）。 */
    public static DhtPeerDiscovery create(List<String> bootstrapNodes) throws IOException {
        DhtClient client = new DhtClient(0);
        DhtPeerDiscovery discovery = new DhtPeerDiscovery(client);
        Thread.ofVirtual().name("javathunder-dht-bootstrap").start(() ->
            client.bootstrap(bootstrapNodes));
        return discovery;
    }

    @Override
    public void warmUp() {
        if (client.knownNodes() == 0) {
            client.bootstrap(DhtClient.DEFAULT_BOOTSTRAP);
        }
    }

    @Override
    public CompletableFuture<List<InetSocketAddress>> getPeers(byte[] infoHash) {
        return client.getPeers(infoHash);
    }

    @Override
    public void close() {
        client.close();
    }
}
