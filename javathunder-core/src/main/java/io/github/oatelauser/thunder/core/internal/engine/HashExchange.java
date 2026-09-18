package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.metainfo.MerkleProofs;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentVersion;
import io.github.oatelauser.thunder.core.internal.wire.HashReject;
import io.github.oatelauser.thunder.core.internal.wire.HashRequest;
import io.github.oatelauser.thunder.core.internal.wire.Hashes;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;

import java.security.MessageDigest;

/**
 * BEP 52 供种侧 hash request 应答（DownloadSession 与 testkit 种子方共用）。
 *
 * <p>只服务 piece-layer 区间请求（base == pieceLayer）：种子从 .torrent 的层带直接
 * 供哈希，越尾部分以零链填充常数补齐，uncle 证明按需现算（见 {@link MerkleProofs}）。
 * 块层（base &lt; pieceLayer）请求需要逐块哈希——本引擎按整件 Merkle 校验、不保存块
 * 哈希，一律 hash reject（对端可回退到层带验证或换源）。
 */
public final class HashExchange {

    private HashExchange() {
    }

    /**
     * 按元数据应答一个 hash request：能服务回 hashes，否则回 hash reject。
     */
    public static PeerWireMessage respond(TorrentMetadata meta, HashRequest request) {
        MerkleProofs.Chunk chunk = lookupChunk(meta, request);
        if (chunk == null) {
            return new HashReject(request.piecesRoot(), request.baseLayer(), request.index(),
                    request.length(), request.proofLayers());
        }
        return new Hashes(request.piecesRoot(), request.baseLayer(), request.index(),
                request.length(), request.proofLayers(), chunk.hashes(), chunk.proof());
    }

    /** 按 root 定位文件并构造区间应答；不可服务（含找不到文件）返回 null。 */
    private static MerkleProofs.Chunk lookupChunk(TorrentMetadata meta, HashRequest request) {
        if (meta.version() == TorrentVersion.V1) {
            return null;
        }
        int pieceLayer = MerkleProofs.log2((int) (meta.pieceLength() / (16 * 1024)));
        for (TorrentMetadata.TorrentFile file : meta.files()) {
            if (file.padding() || file.length() <= 0 || file.pieceLayer() == null) {
                continue; // 填充文件/空文件/单 piece 文件（无层带，根即哈希）
            }
            if (!MessageDigest.isEqual(file.piecesRoot(), request.piecesRoot())) {
                continue;
            }
            int filePieces = (int) ((file.length() + meta.pieceLength() - 1) / meta.pieceLength());
            return MerkleProofs.buildChunk(file.pieceLayer(), filePieces, pieceLayer,
                    request.baseLayer(), request.index(), request.length(), request.proofLayers());
        }
        return null;
    }
}
