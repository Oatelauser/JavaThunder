package io.github.oatelauser.thunder.core.internal.wire;

/**
 * BEP6 快速扩展：对端尚无任何数据（ID 15）。单例。
 */
public final class HaveNone implements PeerWireMessage {

    public static final HaveNone INSTANCE = new HaveNone();

    private HaveNone() {
    }
}
