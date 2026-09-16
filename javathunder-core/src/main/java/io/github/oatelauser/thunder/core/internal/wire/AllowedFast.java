package io.github.oatelauser.thunder.core.internal.wire;

/**
 * BEP 6 快速扩展：对端承诺即使 choke 也会供给的 Piece（ID 17，载荷 = Piece 序号 u32）。
 * 我们侧不发送（无 allowed-fast 集合的实现诉求），解码容忍忽略。
 */
public record AllowedFast(int pieceIndex) implements PeerWireMessage {
}
