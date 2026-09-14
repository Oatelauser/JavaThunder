package io.github.oatelauser.thunder.core.internal.wire;

/** Peer 线协议帧非法（违反 BEP 3 布局、未知消息 ID、超出帧上限）。 */
public class PeerWireException extends RuntimeException {

    public PeerWireException(String message) {
        super(message);
    }
}
