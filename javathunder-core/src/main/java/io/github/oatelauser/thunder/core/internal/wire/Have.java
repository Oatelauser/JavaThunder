package io.github.oatelauser.thunder.core.internal.wire;

/**
 * 对端新完成一个 Piece（ID 4，载荷 = Piece 序号 u32）。
 */
public record Have(int pieceIndex) implements PeerWireMessage {
}
