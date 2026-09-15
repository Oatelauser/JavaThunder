package io.github.oatelauser.thunder.api;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 去中心化 Peer 发现源 SPI（core 消费、可选模块实现——如 javathunder-dht）。
 * core 不依赖任何具体实现：使用者通过 {@code TorrentClient.builder().peerDiscovery(source)}
 * 注入；未注入时引擎只走 tracker（与既有行为一致）。
 */
public interface PeerDiscoverySource extends AutoCloseable {

    /** 网络预热（自举）。实现应尽量快返回，后台完成填充。 */
    default void warmUp() {
    }

    /** 按 info-hash 查找持有该内容的对端。 */
    CompletableFuture<List<InetSocketAddress>> getPeers(byte[] infoHash);

    @Override
    default void close() {
    }
}
