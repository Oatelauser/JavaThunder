package io.github.oatelauser.thunder.core.internal.wire;

/** BEP6 快速扩展：对端拒绝一个请求（ID 16，载荷布局同 request）。引擎应释放对应在途块。 */
public record RejectRequest(int pieceIndex, int begin, int length) implements PeerWireMessage {
}
