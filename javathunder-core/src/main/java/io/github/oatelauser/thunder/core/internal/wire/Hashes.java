package io.github.oatelauser.thunder.core.internal.wire;

import java.util.Arrays;
import java.util.List;

/**
 * BEP 52 hashes（ID 22）：对 hash request 的应答。头 48 字节同请求；其后是 base
 * layer 的 length 个哈希（每个 32B），再后是证明哈希——从被请求区间子树的顶层起，
 * 每层一个 uncle（兄弟）哈希直到 pieces root。证明哈希个数（收发双方按同一公式推导，
 * 与 libtorrent 一致）：{@code proofLayers - log2(length) + 1}；被请求区间覆盖整层时
 * 为 0（区间自身折叠即根）。区间越过文件末 piece 的部分是填充哈希（零链常数）。
 */
public record Hashes(
        byte[] piecesRoot,
        int baseLayer,
        int index,
        int length,
        int proofLayers,
        List<byte[]> hashes,
        List<byte[]> proof) implements PeerWireMessage {

    public Hashes {
        piecesRoot = piecesRoot.clone();
        hashes = List.copyOf(hashes);
        proof = List.copyOf(proof);
    }

    /** byte[]/List&lt;byte[]&gt; 组件按值比较（编解码往返与应答匹配依赖内容相等）。 */
    @Override
    public boolean equals(Object o) {
        return o instanceof Hashes other
                && baseLayer == other.baseLayer && index == other.index
                && length == other.length && proofLayers == other.proofLayers
                && Arrays.equals(piecesRoot, other.piecesRoot)
                && hashListsEqual(hashes, other.hashes)
                && hashListsEqual(proof, other.proof);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(piecesRoot);
        result = 31 * result + Integer.hashCode(baseLayer);
        result = 31 * result + Integer.hashCode(index);
        result = 31 * result + Integer.hashCode(length);
        result = 31 * result + Integer.hashCode(proofLayers);
        result = 31 * result + hashListHashCode(hashes);
        return 31 * result + hashListHashCode(proof);
    }

    private static boolean hashListsEqual(List<byte[]> a, List<byte[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static int hashListHashCode(List<byte[]> list) {
        int result = 1;
        for (byte[] hash : list) {
            result = 31 * result + Arrays.hashCode(hash);
        }
        return result;
    }
}
