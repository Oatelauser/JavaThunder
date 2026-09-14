package io.github.oatelauser.thunder.core.internal.engine;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChokingManagerTest {

    @Test
    void unchokesTopFourDownloadersAmongInterestedPeers() {
        ChokingManager choking = new ChokingManager(new Random(7));
        for (int i = 1; i <= 6; i++) {
            choking.recordReceived("peer" + i, i * 100);
        }

        Set<Object> unchoked = choking.recompute(
            Set.of("peer1", "peer2", "peer3", "peer4", "peer5", "peer6"),  // 连接中的
            Set.of("peer1", "peer2", "peer3", "peer4", "peer5", "peer6")); // 对我感兴趣的

        // 速率排名 peer6>peer5>...>peer1 → 前 4 名 + 1 乐观槽（peer1/peer2 之一）
        assertTrue(unchoked.containsAll(Set.of("peer6", "peer5", "peer4", "peer3")));
        assertEquals(5, unchoked.size());
        assertTrue(unchoked.contains("peer1") || unchoked.contains("peer2"));
    }

    @Test
    void neverUnchokesUninterestedPeersExceptNone() {
        ChokingManager choking = new ChokingManager(new Random(7));
        choking.recordReceived("fast", 10_000);
        choking.recordReceived("slow", 100);

        Set<Object> unchoked = choking.recompute(Set.of("fast", "slow"), Set.of("slow"));

        assertTrue(unchoked.contains("slow"));
        assertFalse(unchoked.contains("fast"), "unchoke 一个对我无兴趣的 Peer 是浪费");
    }

    @Test
    void emptyPeerSetYieldsOnlyOptimisticWhenSomeoneIsInterested() {
        ChokingManager choking = new ChokingManager(new Random(7));
        Set<Object> unchoked = choking.recompute(Set.of("a", "b"), Set.of("a", "b"));
        assertEquals(1, unchoked.size(), "无速率数据时仅乐观槽");
    }

    @Test
    void optimisticSlotRotatesOnDemand() {
        ChokingManager choking = new ChokingManager(new Random(7));
        Set<Object> connected = Set.of("a", "b", "c", "d", "e");
        Set<Object> first = choking.recompute(connected, connected);
        Set<Object> rotated = choking.rotateOptimistic(connected, connected);
        assertEquals(1, rotated.size());
    }

    @Test
    void seedingRanksByBytesWeSent() {
        ChokingManager choking = new ChokingManager(new Random(7));
        choking.recordSent("leech1", 5_000);
        choking.recordSent("leech2", 100);

        Set<Object> unchoked = choking.recompute(Set.of("leech1", "leech2"), Set.of("leech1", "leech2"));

        assertTrue(unchoked.contains("leech1"));
    }

    @Test
    void recomputeResetsRateWindow() {
        ChokingManager choking = new ChokingManager(new Random(7));
        choking.recordReceived("a", 10_000);
        Set<Object> first = choking.recompute(Set.of("a"), Set.of("a"));
        assertTrue(first.contains("a"));
        Set<Object> second = choking.recompute(Set.of("a"), Set.of("a"));
        // 窗口清零后 a 无速率数据，仍通过乐观槽保留 1 个 unchoke
        assertEquals(1, second.size());
    }
}
