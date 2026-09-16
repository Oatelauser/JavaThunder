package io.github.oatelauser.thunder.core.internal.engine;

import java.net.InetSocketAddress;

/**
 * Peer 地址的共用判定与标识：host:port 会话键与自连识别。
 * DownloadSession 与 MetadataFetcher（磁力元数据阶段）共用，保证两条链路的
 * 候选过滤与去重语义一致。
 */
final class PeerAddresses {

    private PeerAddresses() {
    }

    /**
     * 会话键：解析地址用数字 IP，未解析（DNS 失败）回退主机名，仍可作去重键。
     */
    static String key(InetSocketAddress address) {
        return (address.getAddress() != null ? address.getAddress().getHostAddress()
                : address.getHostString()) + ":" + address.getPort();
    }

    /**
     * 自连识别：tracker/PEX/DHT 把我们自己回给我们的形态（回环或通配地址 + 自己的监听端口）。
     */
    static boolean isSelfConnection(InetSocketAddress address, int listenPort) {
        return address.getPort() == listenPort
                && (address.getAddress().isLoopbackAddress() || address.getAddress().isAnyLocalAddress());
    }
}
