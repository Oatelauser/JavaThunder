package io.github.oatelauser.thunder.core.internal.metainfo;

/**
 * 种子形态（BEP 52）：V1 = 传统 SHA-1 逐件哈希；V2 = SHA-256 Merkle 树
 * （file tree + piece layers）；HYBRID = 同一 info 字典内两套并存
 * （双 info-hash：SHA-1 与 SHA-256 对同一份原始字节各算一次）。
 */
public enum TorrentVersion {
    V1, HYBRID, V2
}
