package io.github.oatelauser.thunder.core.internal.wire;

/**
 * BEP 6 快速扩展：对端建议优先下载的 Piece（ID 13，载荷 = Piece 序号 u32）。
 * 我们侧不发送（选件有自己的稀缺度策略），解码容忍忽略。
 */
public record SuggestPiece(int pieceIndex) implements PeerWireMessage {
}
