package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.FileFilter;
import io.github.oatelauser.thunder.api.FilePriority;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentVersion;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选择性下载的件级投影单测：v1 跨界件（重叠即必需）、v2 对齐（精确件集）、
 * 单文件形态、进度分母与完成判定的换基语义。
 */
class WantedPiecesTest {

    private static final long PIECE = 16 * 1024;

    /**
     * v1 多文件布局（拼接流）：a[0,10000) b[10000,50000) c[50000,70000)，
     * 5 件（末件 4464B）——a/c 想要 → 件 0（a 与 b 跨界）、件 3、4（c，件 3 与 b 跨界）。
     */
    @Test
    void v1SpanningPiecesAreRequiredWhenAnyWantedFileOverlaps() {
        TorrentMetadata meta = multiFileMeta(List.of(
                file("a.txt", 0, 10000),
                file("b.bin", 10000, 40000),
                file("c.txt", 50000, 20000)), 70000);
        WantedPieces wanted = new WantedPieces(meta, FileFilter.extensions("txt"), FilePriority.all(FilePriority.NORMAL));

        assertTrue(wanted.requiredPiece(0), "a 覆盖件 0（跨界含 b 的头 6384B）");
        assertFalse(wanted.requiredPiece(1), "纯 b 区间");
        assertFalse(wanted.requiredPiece(2), "纯 b 区间");
        assertTrue(wanted.requiredPiece(3), "c 覆盖件 3（跨界含 b 的尾 848B）");
        assertTrue(wanted.requiredPiece(4), "c 的末件（截断 4464B）");
        assertEquals(2 * PIECE + 4464, wanted.wantedBytes(), "件 0/3 两个整件 + 末件截断");
    }

    @Test
    void alignedFilesMapToExactPieceSet() {
        // v2 语义的对齐布局：每文件恰好整件边界（file tree 保证），b 想要 → 精件 1、2
        TorrentMetadata meta = multiFileMeta(List.of(
                file("a.bin", 0, PIECE),
                file("b.txt", PIECE, 2 * PIECE),
                file("c.bin", 3 * PIECE, PIECE)), 4 * PIECE);
        WantedPieces wanted = new WantedPieces(meta, FileFilter.paths("b.txt"), FilePriority.all(FilePriority.NORMAL));

        assertFalse(wanted.requiredPiece(0));
        assertTrue(wanted.requiredPiece(1));
        assertTrue(wanted.requiredPiece(2));
        assertFalse(wanted.requiredPiece(3));
        assertEquals(2 * PIECE, wanted.wantedBytes());
    }

    @Test
    void singleFileTorrentJudgedByRootName() {
        TorrentMetadata meta = new TorrentMetadata(new byte[20], null, List.of(), null, null,
                null, "model.bin", PIECE, PIECE, new byte[20], false, List.of(), List.of());
        assertTrue(new WantedPieces(meta, FileFilter.paths("model.bin"), FilePriority.all(FilePriority.NORMAL)).requiredPiece(0));
        assertThrows(IllegalArgumentException.class,
                () -> new WantedPieces(meta, FileFilter.paths("other.bin"), FilePriority.all(FilePriority.NORMAL)),
                "排除唯一文件 = 无意义任务，构造期拒绝");
    }

    @Test
    void completionIgnoresLeftoverUnwantedBits() {
        TorrentMetadata meta = multiFileMeta(List.of(
                file("a.txt", 0, PIECE),
                file("b.bin", PIECE, PIECE)), 2 * PIECE);
        WantedPieces wanted = new WantedPieces(meta, FileFilter.paths("a.txt"), FilePriority.all(FilePriority.NORMAL));

        Bitfield local = new Bitfield(2);
        local.set(0);
        assertTrue(wanted.completeAgainst(local), "必需件齐即完成");
        local.set(1); // resume 残留的非必需位
        assertTrue(wanted.completeAgainst(local), "多余位不干扰完成判定");

        Bitfield partial = new Bitfield(2);
        partial.set(1);
        assertFalse(wanted.completeAgainst(partial), "只有非必需件不算完成");
    }

    private static TorrentMetadata.TorrentFile file(String name, long offset, long length) {
        return new TorrentMetadata.TorrentFile(List.of(name), offset, length);
    }

    private static TorrentMetadata multiFileMeta(
            List<TorrentMetadata.TorrentFile> files, long totalLength) {
        byte[] pieces = new byte[(int) ((totalLength + PIECE - 1) / PIECE) * 20];
        return new TorrentMetadata(new byte[20], "http://tk/announce", List.of(), null, null,
                null, "root", totalLength, PIECE, pieces, false, files, List.of(),
                TorrentVersion.V1, null);
    }
    @Test
    void prioritiesProjectToPiecesWithMaxOverSpanning() {
        // a[0,10000)=HIGH, b[10000,50000)=NORMAL, c[50000,70000)=SKIP
        // 件 0 压 a+b → HIGH；件 1/2 纯 b → NORMAL；件 3 压 b+c → NORMAL(max(NORMAL,SKIP))；
        // 件 4 纯 c → 0（不需要）
        TorrentMetadata meta = multiFileMeta(List.of(
                file("a.txt", 0, 10000),
                file("b.bin", 10000, 40000),
                file("c.txt", 50000, 20000)), 70000);
        WantedPieces wanted = new WantedPieces(meta, FileFilter.all(),
                path -> path.get(0).equals("a.txt") ? FilePriority.HIGH
                        : path.get(0).equals("c.txt") ? FilePriority.SKIP : FilePriority.NORMAL);

        assertEquals(FilePriority.HIGH, wanted.priorityOf(0));
        assertEquals(FilePriority.NORMAL, wanted.priorityOf(1));
        assertEquals(FilePriority.NORMAL, wanted.priorityOf(2));
        assertEquals(FilePriority.NORMAL, wanted.priorityOf(3), "跨界件取保留侧的最高优先级");
        assertEquals(0, wanted.priorityOf(4), "SKIP 文件独占的件不参与");
        assertFalse(wanted.requiredPiece(4));
        assertEquals(4 * 16384, wanted.wantedBytes(), "件 0-3 整件");
    }

    @Test
    void allSkipRejectsConstruction() {
        TorrentMetadata meta = multiFileMeta(List.of(file("a.txt", 0, 16384)), 16384);
        assertThrows(IllegalArgumentException.class,
                () -> new WantedPieces(meta, FileFilter.all(), path -> FilePriority.SKIP));
    }
}
