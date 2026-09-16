package io.github.oatelauser.thunder.core.internal.wire;

/**
 * 对端将停止向我提供数据（ID 0）。单例。
 */
public final class Choke implements PeerWireMessage {

    public static final Choke INSTANCE = new Choke();

    private Choke() {
    }
}
