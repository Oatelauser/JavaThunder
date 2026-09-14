package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PieceSchedulerTest {

    // 640B / 256B piece → 3 块，末块 128B
    private static final int PIECE_COUNT = 3;
    private static final long PIECE_LENGTH = 256;
    private static final long TOTAL_LENGTH = 640;

    private static Bitfield all() {
        Bitfield bitfield = new Bitfield(PIECE_COUNT);
        for (int i = 0; i < PIECE_COUNT; i++) {
            bitfield.set(i);
        }
        return bitfield;
    }

    @Test
    void noCandidatesWhenNoPeers() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH, new Random(7));
        assertTrue(scheduler.pick(new Bitfield(PIECE_COUNT)).isEmpty());
    }

    @Test
    void picksRarestPieceAmongCandidates() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH, new Random(7));
        scheduler.peerConnected("a", all());
        Bitfield partial = new Bitfield(PIECE_COUNT);
        partial.set(1); // 只持有 piece 1
        scheduler.peerConnected("b", partial);

        // availability: piece0=1, piece1=2, piece2=1 → 首块随机相位从候选中选
        int first = scheduler.pick(new Bitfield(PIECE_COUNT)).orElseThrow();
        assertTrue(first == 0 || first == 1 || first == 2);

        // 本地已有 first 后，严格稀缺优先：剩下两块 availability 都是 1，但 piece1 有 2 个持有者
        Bitfield local = new Bitfield(PIECE_COUNT);
        local.set(first);
        int second = scheduler.pick(local).orElseThrow();
        if (first == 1) {
            assertTrue(second == 0 || second == 2); // 剩余并列 1，任选
        } else {
            assertEquals(first == 0 ? 2 : 0, second); // piece1 持有者多，被避开
        }
    }

    @Test
    void deterministicRarestSelection() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH, new Random(7));
        Bitfield partialA = new Bitfield(PIECE_COUNT);
        partialA.set(0);
        scheduler.peerConnected("a", partialA);
        Bitfield partialB = new Bitfield(PIECE_COUNT);
        partialB.set(0);
        partialB.set(2);
        scheduler.peerConnected("b", partialB);

        // availability: 0→2, 1→0(不可得), 2→1 ⇒ 必选 2
        assertEquals(2, scheduler.pick(new Bitfield(PIECE_COUNT)).orElseThrow());
    }

    @Test
    void skipsPiecesAlreadyOwnedLocally() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH, new Random(7));
        scheduler.peerConnected("a", all());
        Bitfield local = all();
        assertTrue(scheduler.pick(local).isEmpty());
    }

    @Test
    void haveAndDisconnectUpdateAvailability() {
        PieceScheduler scheduler = new PieceScheduler(PIECE_COUNT, PIECE_LENGTH, TOTAL_LENGTH, new Random(7));
        scheduler.peerConnected("a", new Bitfield(PIECE_COUNT)); // 空位图（对端尚无数据）
        assertEquals(0, scheduler.availability(0));
        scheduler.peerHave("a", 0);
        assertEquals(1, scheduler.availability(0));
        scheduler.peerDisconnected("a");
        assertEquals(0, scheduler.availability(0));
    }

    @Test
    void blocksOfSplitsInto16KiBBlocks() {
        // pieceLength = 32KiB → 2 个 16KiB block；末块 4KiB → 1 个
        PieceScheduler scheduler = new PieceScheduler(3, 32 * 1024, 32 * 1024 + 32 * 1024 + 4 * 1024, new Random(7));
        assertEquals(List.of(
            new BlockRequest(0, 0, 16384),
            new BlockRequest(0, 16384, 16384)), scheduler.blocksOf(0));
        assertEquals(List.of(new BlockRequest(2, 0, 4096)), scheduler.blocksOf(2));
    }

    @Test
    void endgameWhenEveryMissingBlockIsInFlight() {
        PieceScheduler scheduler = new PieceScheduler(1, 256, 256, new Random(7));
        Bitfield local = new Bitfield(1);
        assertFalse(scheduler.isEndgame(local));

        BlockRequest block = new BlockRequest(0, 0, 256);
        scheduler.markInFlight(block);
        assertTrue(scheduler.isEndgame(local));

        scheduler.clearInFlight(block);
        assertFalse(scheduler.isEndgame(local));
    }
}
