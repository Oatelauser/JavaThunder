package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.storage.TorrentStorage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 齐件校验与落盘：组装块顺序喂 SHA-1，与种子 piece 哈希做常时比较
 * （{@link MessageDigest#isEqual}），通过则 gather 各块缓冲写入存储。
 * 纯函数——不改任何会话状态，可安全运行在 Peer 监视器之外（与该 Peer
 * 后续块的事件处理并行）。
 */
final class PieceVerifier {

    private PieceVerifier() {
    }

    /**
     * @return 校验通过且已落盘返回 true；哈希不符返回 false（不落盘——坏件
     *         从未写入磁盘，丢弃组装器即可重下，无清盘成本）
     * @throws IOException 落盘 I/O 失败，原样上抛（调用方据此失败整个任务）
     */
    static boolean verifyAndStore(TorrentStorage storage, TorrentMetadata meta,
            PieceAssembler assembler, int piece) throws IOException {
        byte[] hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (byte[] block : assembler.blocks) {
                digest.update(block);
            }
            hash = digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-1", e);
        }
        if (!MessageDigest.isEqual(hash, meta.pieceHash(piece))) {
            return false;
        }
        ByteBuffer[] buffers = new ByteBuffer[assembler.blocks.length];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = ByteBuffer.wrap(assembler.blocks[i]);
        }
        storage.writePieceBuffers(piece, buffers);
        return true;
    }
}
