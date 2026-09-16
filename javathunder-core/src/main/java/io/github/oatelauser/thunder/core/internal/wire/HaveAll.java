package io.github.oatelauser.thunder.core.internal.wire;

/**
 * BEP6 快速扩展：对端持有全部数据（ID 14，替代全 1 位图）。单例。
 */
public final class HaveAll implements PeerWireMessage {

    public static final HaveAll INSTANCE = new HaveAll();

    private HaveAll() {
    }
}
