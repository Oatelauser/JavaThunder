package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Piece 调度（DESIGN §5.8）：远端位图单源、在途块表与生产选件策略（稀缺优先 +
 * 组装器限流；先到先得的确定性遍历顺序，无随机相位）。
 *
 * <p>并发模型（C5-2）：peer 位图表 / 在途块表走并发容器（无锁读写）；
 * peer 连接生命周期（位图注册/替换/移除）走 {@value #STRIPES} 路 striped lock，
 * 消除 selector 线程上的单监视器串行——16 路并发下 peers 表的结构性变更
 * （连接/断开）仍互斥，但 hot path（availability / inFlight 查询）完全并行。
 */
public final class PieceScheduler {

    public static final int BLOCK_SIZE = 16384;
    private static final int STRIPES = 16;

    private final int pieceCount;
    private final long pieceLength;
    private final long totalLength;
    private final Map<Object, Bitfield> peers = new ConcurrentHashMap<>();
    private final Set<BlockRequest> inFlight = ConcurrentHashMap.newKeySet();
    private final Object[] stripeLocks = new Object[STRIPES];

    public PieceScheduler(int pieceCount, long pieceLength, long totalLength) {
        this.pieceCount = pieceCount;
        this.pieceLength = pieceLength;
        this.totalLength = totalLength;
        for (int i = 0; i < STRIPES; i++) {
            stripeLocks[i] = new Object();
        }
    }

    private Object stripe(Object key) {
        return stripeLocks[Math.floorMod(key.hashCode(), STRIPES)];
    }

    public void peerConnected(Object peerKey, Bitfield remote) {
        if (remote.size() != pieceCount) {
            throw new IllegalArgumentException("remote bitfield size mismatch");
        }
        synchronized (stripe(peerKey)) {
            peers.put(peerKey, remote);
        }
    }

    public void peerHave(Object peerKey, int pieceIndex) {
        Bitfield remote = peers.get(peerKey);
        if (remote != null && pieceIndex >= 0 && pieceIndex < pieceCount) {
            synchronized (stripe(peerKey)) {
                remote.set(pieceIndex);
            }
        }
    }

    public void peerDisconnected(Object peerKey) {
        synchronized (stripe(peerKey)) {
            peers.remove(peerKey);
        }
    }

    /**
     * 远端位图单源：peer 注册的位图（可能 null——调用方自行判空，或只在
     * peerConnected 之后访问）。
     */
    public @Nullable Bitfield remoteOf(Object peerKey) {
        return peers.get(peerKey);
    }

    /** 全部已注册远端位图的弱一致视图（availability 等遍历场景）。 */
    public Iterable<Bitfield> remotes() {
        return peers.values();
    }

    /**
     * 生产选件策略：逐件遍历，跳过本地已有 / 校验中 / 该对端没有 / 已无缺失块的件；
     * 非组装中件在组装器满员（{@code assemblingCount >= maxActivePieces}）时跳过，
     * 否则按 availability 严格小于更新 bestFree（先到先得——并列取先遍历到者，
     * 保持确定性）；组装中件更新 bestBusy。返回 bestFree 优先，无候选返回 -1。
     *
     * <p>并发说明：{@code local} 为调用方传入的 live 位图，此处无锁逐位读——
     * 与 {@link #availability(int)} 同一弱一致级别（并发容器遍历），瞬时陈旧只
     * 影响选择质量，不影响正确性（重复请求由在途表与组装器去重兜底）。
     */
    public int pickFor(Object peerKey, Bitfield local, PieceConstraints constraints) {
        Bitfield remote = peers.get(peerKey);
        int bestFree = -1;
        int bestFreeAvailability = Integer.MAX_VALUE;
        int bestBusy = -1;
        int bestBusyAvailability = Integer.MAX_VALUE;
        for (int i = 0; i < pieceCount; i++) {
            if (local.has(i) || constraints.verifyingPieces().contains(i)
                    || remote == null || !remote.has(i)
                    || !constraints.hasMissingBlock().test(i)) {
                continue;
            }
            int availability = availability(i);
            if (!constraints.activePieces().contains(i)) {
                if (constraints.assemblingCount() >= constraints.maxActivePieces()) {
                    continue; // 组装器满：不开新件（在途件仍可补块）
                }
                if (availability < bestFreeAvailability) {
                    bestFree = i;
                    bestFreeAvailability = availability;
                }
            } else if (availability < bestBusyAvailability) {
                bestBusy = i;
                bestBusyAvailability = availability;
            }
        }
        return bestFree >= 0 ? bestFree : bestBusy;
    }

    /** 无锁热路径：并发容器的 values() 弱一致遍历对计数场景安全。 */
    public int availability(int pieceIndex) {
        int count = 0;
        for (Bitfield remote : peers.values()) {
            if (remote.has(pieceIndex)) {
                count++;
            }
        }
        return count;
    }

    /** 一个 Piece 按 16 KiB 拆分的全部 Block（末块取剩余长度）。纯函数。 */
    public List<BlockRequest> blocksOf(int pieceIndex) {
        long offset = pieceIndex * pieceLength;
        long pieceSize = Math.min(pieceLength, totalLength - offset);
        List<BlockRequest> blocks = new ArrayList<>();
        for (int begin = 0; begin < pieceSize; begin += BLOCK_SIZE) {
            blocks.add(new BlockRequest(pieceIndex, begin, (int) Math.min(BLOCK_SIZE, pieceSize - begin)));
        }
        return List.copyOf(blocks);
    }

    /** 在途块表：并发容器，无锁（add/remove/contains 单键原子）。 */
    public void markInFlight(BlockRequest request) {
        inFlight.add(request);
    }

    public void clearInFlight(BlockRequest request) {
        inFlight.remove(request);
    }

    public boolean isInFlight(BlockRequest request) {
        return inFlight.contains(request);
    }
}
