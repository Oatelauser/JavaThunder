package io.github.oatelauser.thunder.core.internal.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 标准 tit-for-tat choking（DESIGN §5.9）：每轮 unchoke 对我上传最多的 4 个 Peer
 * （做种期改为从我下载最多的 4 个）+ 1 个乐观槽（给新 Peer 机会，防止冷启动死锁）。
 * 只 unchoke 对我感兴趣的 Peer。线程安全。
 */
public final class ChokingManager {

    private static final int RECIPROCATION_SLOTS = 4;

    private final Random random;
    /** 速率记账走并发容器（C5-2）：每块一次的 record* 不再进监视器。 */
    private final java.util.concurrent.ConcurrentMap<Object, Long> receivedFromPeer =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Object, Long> sentToPeer =
        new java.util.concurrent.ConcurrentHashMap<>();
    private Object optimisticPeer;

    public ChokingManager(Random random) {
        this.random = random;
    }

    public void recordReceived(Object peerKey, int bytes) {
        receivedFromPeer.merge(peerKey, (long) bytes, Long::sum);
    }

    public void recordSent(Object peerKey, int bytes) {
        sentToPeer.merge(peerKey, (long) bytes, Long::sum);
    }

    /**
     * 周期重算（引擎每 10s 调一次）。返回新的 unchoke 集合（含乐观槽），
     * 同时清零速率窗口。仅从未对我感兴趣的候选中排除——兴趣由引擎维护。
     */
    public synchronized Set<Object> recompute(Set<Object> connected, Set<Object> interestedInUs) {
        receivedFromPeer.keySet().retainAll(connected);
        sentToPeer.keySet().retainAll(connected);

        Map<Object, Long> rates = new HashMap<>();
        receivedFromPeer.forEach((peer, bytes) -> rates.merge(peer, bytes, Long::sum));
        // 做种互惠：把上传量也纳入排序依据（下载数据的一方同时记录两个方向）
        Set<Object> unchoked = new HashSet<>();
        List<Object> ranked = new ArrayList<>();
        for (Object peer : interestedInUs) {
            long rate = receivedFromPeer.getOrDefault(peer, 0L) + sentToPeer.getOrDefault(peer, 0L);
            if (rate > 0) {
                ranked.add(peer);
            }
        }
        ranked.sort(Comparator.comparingLong((Object peer) ->
            receivedFromPeer.getOrDefault(peer, 0L) + sentToPeer.getOrDefault(peer, 0L)).reversed());
        for (Object peer : ranked) {
            if (unchoked.size() >= RECIPROCATION_SLOTS) {
                break;
            }
            unchoked.add(peer);
        }
        pickOptimistic(connected, interestedInUs, unchoked);
        receivedFromPeer.clear();
        sentToPeer.clear();
        return unchoked;
    }

    /** 乐观槽轮换（引擎每 30s 调一次）。 */
    public synchronized Set<Object> rotateOptimistic(Set<Object> connected, Set<Object> interestedInUs) {
        Set<Object> unchoked = new HashSet<>(currentTopReciprocators(interestedInUs));
        pickOptimistic(connected, interestedInUs, unchoked);
        return unchoked;
    }

    private Set<Object> currentTopReciprocators(Set<Object> interestedInUs) {
        // 轮换只换乐观槽；互惠槽沿用轮内累计数据（零贡献者不占互惠槽）。
        Set<Object> top = new HashSet<>();
        List<Object> ranked = new ArrayList<>();
        for (Object peer : interestedInUs) {
            long rate = receivedFromPeer.getOrDefault(peer, 0L) + sentToPeer.getOrDefault(peer, 0L);
            if (rate > 0) {
                ranked.add(peer);
            }
        }
        ranked.sort(Comparator.comparingLong(peer ->
            receivedFromPeer.getOrDefault(peer, 0L) + sentToPeer.getOrDefault(peer, 0L)).reversed());
        for (Object peer : ranked) {
            if (top.size() >= RECIPROCATION_SLOTS) {
                break;
            }
            top.add(peer);
        }
        return top;
    }

    private void pickOptimistic(Set<Object> connected, Set<Object> interestedInUs, Set<Object> unchoked) {
        List<Object> candidates = new ArrayList<>();
        for (Object peer : connected) {
            if (interestedInUs.contains(peer) && !unchoked.contains(peer)) {
                candidates.add(peer);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        optimisticPeer = candidates.get(random.nextInt(candidates.size()));
        unchoked.add(optimisticPeer);
    }
}
