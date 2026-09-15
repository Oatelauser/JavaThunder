package io.github.oatelauser.thunder.core.internal.wire;

/** BEP 3 线协议消息总和类型（含 BEP6 容忍性解码）。 */
public sealed interface PeerWireMessage permits
    KeepAlive, Choke, Unchoke, Interested, NotInterested,
    Have, BitfieldMessage, Request, PieceMessage, Cancel,
    HaveAll, HaveNone, RejectRequest, UnsupportedMessage {
}
