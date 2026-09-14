package io.github.oatelauser.thunder.core.internal.wire;

/** 取消一个已发出的 Block 请求（ID 8，endgame 收到重复块后取消其余请求）。 */
public record Cancel(int pieceIndex, int begin, int length) implements PeerWireMessage {
}
