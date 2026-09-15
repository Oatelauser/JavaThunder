package io.github.oatelauser.thunder.core.internal.peer.transport;

import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * 一条已完成握手的 Peer 连接。线程安全。
 *
 * <p>推送模型：实现负责在连接线程/事件循环上回调消息监听器；
 * {@code setMessageListener}/{@code setCloseListener} 必须在
 * {@link TransportHandler#onConnected} 回调返回之前完成，实现保证在那之前不投递任何消息。
 */
public interface PeerChannel extends AutoCloseable {

    InetSocketAddress remoteAddress();

    byte[] remotePeerId();

    /** 发送一条消息（可能排队，由实现决定何时刷出）。 */
    void write(PeerWireMessage message);

    /** 批量发送：NIO 实现合成单缓冲一次刷出，减少唤醒与队列开销。 */
    default void write(java.util.List<PeerWireMessage> messages) {
        for (PeerWireMessage message : messages) {
            write(message);
        }
    }

    /** 关闭连接；幂等；触发 closeListener（cause=null 表示主动关闭）。 */
    void close();

    void setMessageListener(Consumer<PeerWireMessage> listener);

    void setCloseListener(Consumer<@Nullable Throwable> listener);
}
