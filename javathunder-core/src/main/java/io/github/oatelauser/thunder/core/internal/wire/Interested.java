package io.github.oatelauser.thunder.core.internal.wire;

/** 我对对端持有感兴趣的数据（ID 2）。单例。 */
public final class Interested implements PeerWireMessage {

    public static final Interested INSTANCE = new Interested();

    private Interested() {
    }
}
