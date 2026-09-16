package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import io.github.oatelauser.thunder.core.internal.wire.BitfieldMessage;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import io.github.oatelauser.thunder.core.internal.wire.Request;
import io.github.oatelauser.thunder.core.internal.wire.Unchoke;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;

/**
 * testkit 种子方的共用行为——数据分块读与开场通告。
 */
public final class SeederCore {

    private SeederCore() {
    }

    /**
     * 按文件分块读：positioned channel 读（D2：内存与文件大小无关）。
     */
    public static byte[] readBlock(FileChannel content, TorrentMetadata meta, Request request) throws IOException {
        long offset = request.pieceIndex() * meta.pieceLength() + request.begin();
        ByteBuffer buffer = ByteBuffer.allocate(request.length());
        while (buffer.hasRemaining()) {
            if (content.read(buffer, offset + buffer.position()) < 0) {
                throw new IOException("unexpected eof at " + (offset + buffer.position()));
            }
        }
        return buffer.array();
    }

    /**
     * 种子方开场通告——满位图 + 立即 unchoke（声明全量持有、无条件供块）。
     */
    public static List<PeerWireMessage> seederHello(TorrentMetadata meta) {
        return List.of(new BitfieldMessage(Bitfield.allSet(meta.pieceCount()).toBytes()), Unchoke.INSTANCE);
    }
}
