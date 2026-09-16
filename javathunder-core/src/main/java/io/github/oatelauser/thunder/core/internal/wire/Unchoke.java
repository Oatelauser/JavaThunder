package io.github.oatelauser.thunder.core.internal.wire;

/**
 * 对端恢复向我提供数据（ID 1）。单例。
 */
public final class Unchoke implements PeerWireMessage {

    public static final Unchoke INSTANCE = new Unchoke();

    private Unchoke() {
    }
}
