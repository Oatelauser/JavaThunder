package io.github.oatelauser.thunder.core.internal.peer.transport;

import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一条已完成握手的 Peer 连接。线程安全。
 *
 * <p>推送模型（批量化）：实现按批回调消息——NIO 一次读批的帧合成一个 List 一次投递，
 * 阻塞实现每批一条。{@code setMessageListener}/{@code setCloseListener} 必须在
 * {@link TransportHandler#onConnected} 回调返回之前完成，实现保证在那之前不投递任何消息。
 * 批内消息保持线序。
 */
public interface PeerChannel extends AutoCloseable {

    InetSocketAddress remoteAddress();

    byte[] remotePeerId();

    /**
     * 对端握手保留位是否声明支持 BEP 10 扩展协议（未握手完成前 false）。
     */
    default boolean remoteSupportsExtensions() {
        return false;
    }

    /**
     * 对端握手保留位是否声明支持 BEP 6 快速扩展（未握手完成前 false；
     * HaveAll/HaveNone/Reject 只对双方都声明的连接使用）。
     */
    default boolean remoteSupportsFast() {
        return false;
    }

    /**
     * 发送一条消息（可能排队，由实现决定何时刷出）。
     */
    void write(PeerWireMessage message);

    /**
     * 批量发送：NIO 实现合成单缓冲一次刷出，减少唤醒与队列开销。
     */
    default void write(List<PeerWireMessage> messages) {
        for (PeerWireMessage message : messages) {
            write(message);
        }
    }

    /**
     * 关闭连接；幂等；触发 closeListener（cause=null 表示主动关闭）。
     */
    @Override
    void close();

    void setMessageListener(Consumer<List<PeerWireMessage>> listener);

    void setCloseListener(Consumer<@Nullable Throwable> listener);
}
