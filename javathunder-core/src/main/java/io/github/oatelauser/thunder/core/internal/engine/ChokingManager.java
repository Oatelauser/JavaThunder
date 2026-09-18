package io.github.oatelauser.thunder.core.internal.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 标准 tit-for-tat choking（DESIGN §5.9）：每轮 unchoke 对我上传最多的 4 个 Peer
 * （做种期改为从我下载最多的 4 个）+ 1 个乐观槽（给新 Peer 机会，防止冷启动死锁）。
 * 只 unchoke 对我感兴趣的 Peer。线程安全。
 */
public final class ChokingManager {

    private static final int RECIPROCATION_SLOTS = 4;

    private final Random random;
    private final ConcurrentMap<Object, Long> sentToPeer = new ConcurrentHashMap<>();
    /**
     * 速率记账走并发容器（C5-2）：每块一次的 record* 不再进监视器。
     */
    private final ConcurrentMap<Object, Long> receivedFromPeer = new ConcurrentHashMap<>();

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

        // 做种互惠：排序依据同时计入两个方向的传输量（见 reciprocationOf）
        Set<Object> unchoked = topReciprocators(interestedInUs);
        pickOptimistic(connected, interestedInUs, unchoked);
        receivedFromPeer.clear();
        sentToPeer.clear();
        return unchoked;
    }

    /**
     * 乐观槽轮换（引擎每 30s 调一次）。
     */
    public synchronized Set<Object> rotateOptimistic(Set<Object> connected, Set<Object> interestedInUs) {
        Set<Object> unchoked = topReciprocators(interestedInUs);
        pickOptimistic(connected, interestedInUs, unchoked);
        return unchoked;
    }

    /**
     * 互惠槽集合：轮换只换乐观槽；互惠槽沿用轮内累计数据（零贡献者不占互惠槽）。
     */
    private Set<Object> topReciprocators(Set<Object> interestedInUs) {
        List<Object> ranked = new ArrayList<>(interestedInUs.size());
        for (Object peer : interestedInUs) {
            if (reciprocationOf(peer) > 0) {
                ranked.add(peer);
            }
        }
        ranked.sort(Comparator.comparingLong(this::reciprocationOf).reversed());
        Set<Object> top = new HashSet<>();
        for (Object peer : ranked) {
            if (top.size() >= RECIPROCATION_SLOTS) {
                break;
            }
            top.add(peer);
        }
        return top;
    }

    /** 本窗口内与该 Peer 的双向传输量（下载数据计入收，做种数据计入发）。 */
    private long reciprocationOf(Object peer) {
        return receivedFromPeer.getOrDefault(peer, 0L) + sentToPeer.getOrDefault(peer, 0L);
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
        unchoked.add(candidates.get(random.nextInt(candidates.size())));
    }
}
