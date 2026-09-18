package io.github.oatelauser.thunder.core.internal.wire;

import java.util.Arrays;

/**
 * BEP 52 hash request（ID 21）：向对端请求某文件 Merkle 哈希树一个区间的哈希与证明。
 * 载荷（48 字节，整数均为 4 字节大端，与 libtorrent 参考实现一致）：
 * pieces root(32B) + base layer + index + length + proof layers。
 * base layer = 请求的最低层距叶子层（16KiB 块层）的层数；length 为 2 的幂（≤512 为宜），
 * index 对齐 length；proof layers = 其上要附带的 uncle 层数。协商前提：双方握手保留位
 * reserved[7]&0x10（BEP 52 协议位）均已置位。
 */
public record HashRequest(
        byte[] piecesRoot,
        int baseLayer,
        int index,
        int length,
        int proofLayers) implements PeerWireMessage {

    public HashRequest {
        piecesRoot = piecesRoot.clone();
    }

    /** byte[] 组件按值比较（编解码往返测试与请求去重依赖内容相等）。 */
    @Override
    public boolean equals(Object o) {
        return o instanceof HashRequest other
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
