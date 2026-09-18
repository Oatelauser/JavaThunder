package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PieceSchedulerTest {

    // 3 件 × 16 KiB：与生产 BLOCK_SIZE 对齐
    private static final int PIECE_COUNT = 3;
    private static final long PIECE_LENGTH = 16384;
    private static final long TOTAL_LENGTH = 3 * 16384;
    /** 全部件同优先级（= 无优先级语义的既有行为）。 */
    private static final IntUnaryOperator NORMAL = piece -> 1;

    private static Bitfield of(int... pieces) {
        Bitfield bitfield = new Bitfield(PIECE_COUNT);
        for (int piece : pieces) {
            bitfield.set(piece);
        }
        return bitfield;
    }

    /** 全件都仍有缺失块的默认约束。 */
    private static PieceConstraints noConstraints() {
        return new PieceConstraints(Set.of(), Set.of(), 0, 4, piece -> true);
    }

    @Test
    void pickForPrefersRarestPiece() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("peerA", of(0, 1));
        scheduler.peerConnected("peerB", of(1));

        // availability: piece0=1（仅 peerA），piece1=2，piece2=0（无人有）→ 必选 0
        assertEquals(0, scheduler.pickFor("peerA", new Bitfield(PIECE_COUNT), NORMAL, noConstraints()));
    }

    @Test
    void pickSequentialForTakesLowestEligibleIndexRegardlessOfRarity() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("peerA", of(1, 2)); // 件 0 无人持有（含 peerA）

        // 稀缺优先会选 availability=1 的件 1/2 之一；顺序取对端有的最小索引 → 1
        assertEquals(1, scheduler.pickSequentialFor("peerA",
                new Bitfield(PIECE_COUNT), NORMAL, noConstraints()));

        // 本地已有件 1：下一个是件 2（校验中的件 0 仍被隐藏，但无人持有本就不可选）
        assertEquals(2, scheduler.pickSequentialFor("peerA", of(1), NORMAL, noConstraints()));
        assertEquals(-1, scheduler.pickSequentialFor("peerA", of(1, 2), NORMAL, noConstraints()));
    }

    @Test
    void pickSequentialForPrefersAssemblingPieceAndHonoursCap() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("peerA", of(0, 1, 2));

        // 组装器满（0/1）：不开新件；件 2 在途（activePieces）仍是候选且索引最大
        PieceConstraints capReached = new PieceConstraints(
                Set.of(2), Set.of(), 2, 2, piece -> true);
        assertEquals(2, scheduler.pickSequentialFor("peerA",
                new Bitfield(PIECE_COUNT), NORMAL, capReached), "在途件绕过组装器上限");

        // 组装器满且无在途件：无候选
        assertEquals(-1, scheduler.pickSequentialFor("peerA",
                new Bitfield(PIECE_COUNT), NORMAL,
                new PieceConstraints(Set.of(), Set.of(), 2, 2, piece -> true)));

        // 常态：件 0 在途未收齐（仍有缺失块）→ 顺序优先收尾手头件
        PieceConstraints assemblingFirst = new PieceConstraints(
                Set.of(0), Set.of(), 1, 2, piece -> true);
        assertEquals(0, scheduler.pickSequentialFor("peerA",
                new Bitfield(PIECE_COUNT), NORMAL, assemblingFirst));
    }

    @Test
    void pickForSkipsLocalAndVerifyingPieces() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("peerA", of(0, 1, 2));

        Bitfield local = of(0);
        assertEquals(1, scheduler.pickFor("peerA", local, NORMAL, noConstraints()));

        // 件 1 进入待校验集合：从选择中隐藏，跳到件 2
        PieceConstraints verifying = new PieceConstraints(
                Set.of(), Set.of(1), 0, 4, piece -> true);
        assertEquals(2, scheduler.pickFor("peerA", local, NORMAL, verifying));

        // 全部本地已有：无候选
        assertEquals(-1, scheduler.pickFor("peerA", of(0, 1, 2), NORMAL, noConstraints()));
    }

    @Test
    void pickForSkipsPiecesWithoutMissingBlocks() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("peerA", of(0, 1, 2));

        // 件 0 的块已收齐（hasMissingBlock=false），跳到件 1
        PieceConstraints halfDone = new PieceConstraints(
                Set.of(), Set.of(), 0, 4, piece -> piece != 0);
        assertEquals(1, scheduler.pickFor("peerA", new Bitfield(PIECE_COUNT), NORMAL, halfDone));
    }

    @Test
    void pickForHonoursAssemblerCap() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("peerA", of(0, 1, 2));

        // 组装器满员（1/1）且无在途件：不开新件 → -1
        PieceConstraints full = new PieceConstraints(
                Set.of(), Set.of(), 1, 1, piece -> true);
        assertEquals(-1, scheduler.pickFor("peerA", new Bitfield(PIECE_COUNT), NORMAL, full));

        // 件 2 在组装中（active）：满员也允许补块 → 只能选 2
        PieceConstraints withActive = new PieceConstraints(
                Set.of(2), Set.of(), 1, 1, piece -> true);
        assertEquals(2, scheduler.pickFor("peerA", new Bitfield(PIECE_COUNT), NORMAL, withActive));
    }

    @Test
    void pickForPrefersHigherPriorityOverRarity() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        // availability: 件 0=2（最不稀缺）但高优先级；件 1/2=1 更稀缺但常规
        scheduler.peerConnected("peerA", of(0, 1, 2));
        scheduler.peerConnected("peerB", of(0));
        IntUnaryOperator priorities = piece -> piece == 0 ? 2 : 1;

        assertEquals(0, scheduler.pickFor("peerA", new Bitfield(PIECE_COUNT),
                priorities, noConstraints()), "优先级压过稀缺度");

        // 高优先级件落定后回到常规集合：件 1（availability 1）胜件 2（同为 1，
        // 先遍历到者——确定性）
        assertEquals(1, scheduler.pickFor("peerA", of(0), NORMAL, noConstraints()));
    }

    @Test
    void pickSequentialForPrefersHigherPriorityOverIndex() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("peerA", of(0, 1, 2));
        // 件 2 高优先级：先于索引更小的件 0/1；[2,2,3] 形态也覆盖"晚出现更高优先级"
        IntUnaryOperator lateHigh = piece -> piece == 2 ? 3 : 2;
        assertEquals(2, scheduler.pickSequentialFor("peerA",
                new Bitfield(PIECE_COUNT), lateHigh, noConstraints()));
        // 高优先级完成后回到最小索引
        assertEquals(0, scheduler.pickSequentialFor("peerA",
                of(2), NORMAL, noConstraints()));
    }

    @Test
    void pickSkipsZeroPriorityPieces() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("peerA", of(0, 1, 2));
        IntUnaryOperator skipFirst = piece -> piece == 0 ? 0 : 1;
        assertEquals(1, scheduler.pickFor("peerA", new Bitfield(PIECE_COUNT),
                skipFirst, noConstraints()), "优先级 0 = 不选");
        assertEquals(1, scheduler.pickSequentialFor("peerA",
                new Bitfield(PIECE_COUNT), skipFirst, noConstraints()));
    }

    @Test
    void pickForUnknownPeerYieldsNothing() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("peerA", of(0, 1, 2));
        // 未注册 peer 视作空位图：全部跳过
        assertEquals(-1, scheduler.pickFor("nobody", new Bitfield(PIECE_COUNT), NORMAL, noConstraints()));
    }

    @Test
    void remoteOfExposesRegisteredBitfield() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        assertNull(scheduler.remoteOf("peerA"));
        Bitfield remote = of(0);
        scheduler.peerConnected("peerA", remote);
        assertEquals(remote, scheduler.remoteOf("peerA"));
        scheduler.peerDisconnected("peerA");
        assertNull(scheduler.remoteOf("peerA"));
    }

    @Test
    void haveAndDisconnectUpdateAvailability() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("a", new Bitfield(PIECE_COUNT)); // 空位图（对端尚无数据）
        assertEquals(0, scheduler.availability(0));
        scheduler.peerHave("a", 0);
        assertEquals(1, scheduler.availability(0));
        scheduler.peerDisconnected("a");
        assertEquals(0, scheduler.availability(0));
    }

    @Test
    void remotesIteratesAllRegisteredBitfields() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        scheduler.peerConnected("a", of(0));
        scheduler.peerConnected("b", of(1));
        int count = 0;
        for (Bitfield ignored : scheduler.remotes()) {
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    void blocksOfSplitsInto16KiBBlocks() {
        // pieceLength = 32KiB → 2 个 16KiB block；末块 4KiB → 1 个
        PieceScheduler scheduler = new PieceScheduler(3, 32 * 1024, 32 * 1024 + 32 * 1024 + 4 * 1024);
        assertEquals(List.of(
            new BlockRequest(0, 0, 16384),
            new BlockRequest(0, 16384, 16384)), scheduler.blocksOf(0));
        assertEquals(List.of(new BlockRequest(2, 0, 4096)), scheduler.blocksOf(2));
    }

    @Test
    void inFlightBlocksAreTrackedUntilCleared() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH);
        BlockRequest block = new BlockRequest(0, 0, 16384);
        assertFalse(scheduler.isInFlight(block));
        scheduler.markInFlight(block);
        assertTrue(scheduler.isInFlight(block));
        scheduler.clearInFlight(block);
        assertFalse(scheduler.isInFlight(block));
    }
}
