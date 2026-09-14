package io.github.oatelauser.thunder.core.internal.wire;

/** 请求一个 Block（ID 6，载荷 = piece 序号 + 块内偏移 + 长度，各 u32；长度恒 16KiB）。 */
public record Request(int pieceIndex, int begin, int length) implements PeerWireMessage {
}
