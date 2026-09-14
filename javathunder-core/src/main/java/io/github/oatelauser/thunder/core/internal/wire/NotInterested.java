package io.github.oatelauser.thunder.core.internal.wire;

/** 我对对端持有的数据不再感兴趣（ID 3）。单例。 */
public final class NotInterested implements PeerWireMessage {

    public static final NotInterested INSTANCE = new NotInterested();

    private NotInterested() {
    }
}
