package io.github.oatelauser.thunder.core.internal.wire;

import java.util.Arrays;

/**
 * BEP 52 hash reject（ID 23）：对端拒绝服务某个 hash request（无该文件/形状不合法/
 * 哈希不可得）。载荷与被拒的 hash request 完全相同（48 字节）。
 */
public record HashReject(
        byte[] piecesRoot,
        int baseLayer,
        int index,
        int length,
        int proofLayers) implements PeerWireMessage {

    public HashReject {
        piecesRoot = piecesRoot.clone();
    }

    /** byte[] 组件按值比较（编解码往返测试依赖内容相等）。 */
    @Override
    public boolean equals(Object o) {
        return o instanceof HashReject other
                && baseLayer == other.baseLayer && index == other.index
                && length == other.length && proofLayers == other.proofLayers
                && Arrays.equals(piecesRoot, other.piecesRoot);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(piecesRoot);
        result = 31 * result + Integer.hashCode(baseLayer);
        result = 31 * result + Integer.hashCode(index);
        result = 31 * result + Integer.hashCode(length);
        return 31 * result + Integer.hashCode(proofLayers);
    }
}
