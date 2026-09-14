package io.github.oatelauser.thunder.core.internal.peer.transport;

import java.net.InetSocketAddress;

/** 连接建立结果回调（出站）。 */
public interface TransportHandler {

    /** 握手完成、消息通道就绪（在实现方的连接线程上回调）。 */
    void onConnected(PeerChannel channel);

    void onConnectFailed(InetSocketAddress address, Throwable cause);
}
