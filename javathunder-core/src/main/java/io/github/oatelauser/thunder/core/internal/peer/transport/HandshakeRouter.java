package io.github.oatelauser.thunder.core.internal.peer.transport;

import org.jspecify.annotations.Nullable;

/** 入站连接路由：按 info-hash 找到对应会话的处理器；返回 null 表示拒收。 */
@FunctionalInterface
public interface HandshakeRouter {

    @Nullable
    TransportHandler route(byte[] infoHash);
}
