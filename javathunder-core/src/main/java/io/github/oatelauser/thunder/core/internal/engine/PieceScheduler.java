package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.storage.Bitfield;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Piece 调度（DESIGN §5.8）：首块随机 → 稀缺优先（rarest-first，并列随机）。
 *
 * <p>并发模型（C5-2）：peer 位图表 / 在途块表走并发容器（无锁读写）；
 * 调度类操作（pick / peer 连接生命周期）走 {@value #STRIPES} 路 striped lock，
 * 消除 selector 线程上的单监视器串行——16 路并发下 peers 表的结构性变更
 * （连接/断开）仍互斥，但 hot path（availability / inFlight 查询）完全并行。
 */
public final class PieceScheduler {

    public static final int BLOCK_SIZE = 16384;
    private static final int STRIPES = 16;

    private final int pieceCount;
    private final long pieceLength;
    private final long totalLength;
    private final Random random;
    private final Map<Object, Bitfield> peers = new ConcurrentHashMap<>();
    private final Set<BlockRequest> inFlight = ConcurrentHashMap.newKeySet();
    private final Object[] stripeLocks = new Object[STRIPES];
    private volatile boolean firstPiecePicked = false;

    public PieceScheduler(int pieceCount, long pieceLength, long totalLength, Random random) {
        this.pieceCount = pieceCount;
        this.pieceLength = pieceLength;
        this.totalLength = totalLength;
        this.random = random;
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

    /** 选下一个要下载的 Piece；无候选返回空。 */
    public OptionalInt pick(Bitfield local) {
        synchronized (stripe(local)) {
            int best = -1;
            int bestAvailability = Integer.MAX_VALUE;
            int candidateCount = 0;
            for (int i = 0; i < pieceCount; i++) {
                if (local.has(i)) {
                    continue;
                }
                int availability = availability(i);
                if (availability == 0) {
                    continue;
                }
                candidateCount++;
                if (firstPiecePicked && availability < bestAvailability) {
                    best = i;
                    bestAvailability = availability;
                }
            }
            if (candidateCount == 0) {
                return OptionalInt.empty();
            }
            if (!firstPiecePicked) {
                firstPiecePicked = true;
                return OptionalInt.of(randomPiece(candidates(local)));
            }
            if (best >= 0 && bestAvailability < Integer.MAX_VALUE) {
                final int min = bestAvailability;
                List<Integer> tied = new ArrayList<>();
                for (int i = 0; i < pieceCount; i++) {
                    if (!local.has(i) && availability(i) == min) {
                        tied.add(i);
                    }
                }
                return OptionalInt.of(tied.get(random.nextInt(tied.size())));
            }
            return OptionalInt.empty();
        }
    }

    private List<Integer> candidates(Bitfield local) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < pieceCount; i++) {
            if (!local.has(i) && availability(i) > 0) {
                result.add(i);
            }
        }
        return result;
    }

    private int randomPiece(List<Integer> candidates) {
        return candidates.get(random.nextInt(candidates.size()));
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

    /** endgame：本地所有缺失 Block 均已有在途请求。 */
    public boolean isEndgame(Bitfield local) {
        for (int i = 0; i < pieceCount; i++) {
            if (local.has(i)) {
                continue;
            }
            for (BlockRequest block : blocksOf(i)) {
                if (!inFlight.contains(block)) {
                    return false;
                }
            }
        }
        return true;
    }
}
