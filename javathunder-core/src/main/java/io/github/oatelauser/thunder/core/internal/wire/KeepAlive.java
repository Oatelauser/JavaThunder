package io.github.oatelauser.thunder.core.internal.wire;

/**
 * keep-alive：零长度帧（4 字节全零长度前缀）。单例。
 */
public final class KeepAlive implements PeerWireMessage {

    public static final KeepAlive INSTANCE = new KeepAlive();

    private KeepAlive() {
    }
}
