package io.github.oatelauser.thunder.core.internal.wire;

/**
 * BEP 3 线协议消息总和类型（含 BEP 6 快速扩展与 BEP 52 哈希交换消息）。
 */
public sealed interface PeerWireMessage permits
        KeepAlive, Choke, Unchoke, Interested, NotInterested,
        Have, BitfieldMessage, Request, PieceMessage, Cancel,
        SuggestPiece, HaveAll, HaveNone, RejectRequest, AllowedFast,
        ExtendedMessage, HashRequest, Hashes, HashReject, UnsupportedMessage {
}
