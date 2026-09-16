package io.github.oatelauser.thunder.core.internal.peer.transport;

import java.net.InetSocketAddress;

/**
 * Peer 连接传输层（ADR-0003）。两种实现：
 * {@link BlockingTransport}（每连接一虚拟线程的参照实现）与 NIO 事件循环实现（C2）。
 * 线程安全；一个实例承载全部任务的连接。
 */
public interface PeerTransport extends AutoCloseable {

    /**
     * 绑定入站监听端口。
     *
     * @param preferredPort 期望端口；传 0 由系统分配
     * @param router        入站握手路由
     * @return 实际绑定端口
     */
    int listen(int preferredPort, HandshakeRouter router);

    /**
     * 发起出站连接（含握手，目标 info-hash 按连接传入——一个 transport 服务多个会话）。
     */
    void connect(InetSocketAddress address, byte[] infoHash, TransportHandler handler);

    /**
     * 已绑定的监听端口；未监听返回 -1。
     */
    int listeningPort();

    @Override
    void close();
}
