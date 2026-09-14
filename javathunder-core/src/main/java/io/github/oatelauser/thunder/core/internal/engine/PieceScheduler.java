package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.storage.Bitfield;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;

/**
 * Piece 调度（DESIGN §5.8）：首块随机（尽快凑出可交换数据）→ 稀缺优先（rarest-first，
 * 并列随机）→ endgame（缺失 Block 全部在途时进入终局，由引擎向多 Peer 重复请求）。
 * 线程安全：引擎的 Peer 线程与定时器线程并发调用。
 */
public final class PieceScheduler {

    public static final int BLOCK_SIZE = 16384;

    private final int pieceCount;
    private final long pieceLength;
    private final long totalLength;
    private final Random random;
    private final Map<Object, Bitfield> peers = new HashMap<>();
    private final Set<BlockRequest> inFlight = new HashSet<>();
    private boolean firstPiecePicked = false;

    public PieceScheduler(int pieceCount, long pieceLength, long totalLength, Random random) {
        this.pieceCount = pieceCount;
        this.pieceLength = pieceLength;
        this.totalLength = totalLength;
        this.random = random;
    }

    public synchronized void peerConnected(Object peerKey, Bitfield remote) {
        if (remote.size() != pieceCount) {
            throw new IllegalArgumentException("remote bitfield size mismatch");
        }
        peers.put(peerKey, remote);
    }

    public synchronized void peerHave(Object peerKey, int pieceIndex) {
        Bitfield remote = peers.get(peerKey);
        if (remote != null && pieceIndex >= 0 && pieceIndex < pieceCount) {
            remote.set(pieceIndex);
        }
    }

    public synchronized void peerDisconnected(Object peerKey) {
        peers.remove(peerKey);
    }

    /** 选下一个要下载的 Piece；无候选返回空。 */
    public synchronized OptionalInt pick(Bitfield local) {
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
            // 并列最稀缺中随机
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

    public synchronized int availability(int pieceIndex) {
        int count = 0;
        for (Bitfield remote : peers.values()) {
            if (remote.has(pieceIndex)) {
                count++;
            }
        }
        return count;
    }

    /** 一个 Piece 按 16 KiB 拆分的全部 Block（末块取剩余长度）。 */
    public List<BlockRequest> blocksOf(int pieceIndex) {
        long offset = pieceIndex * pieceLength;
        long pieceSize = Math.min(pieceLength, totalLength - offset);
        List<BlockRequest> blocks = new ArrayList<>();
        for (int begin = 0; begin < pieceSize; begin += BLOCK_SIZE) {
            blocks.add(new BlockRequest(pieceIndex, begin, (int) Math.min(BLOCK_SIZE, pieceSize - begin)));
        }
        return List.copyOf(blocks);
    }

    public synchronized void markInFlight(BlockRequest request) {
        inFlight.add(request);
    }

    public synchronized void clearInFlight(BlockRequest request) {
        inFlight.remove(request);
    }

    /** endgame：本地所有缺失 Block 均已有在途请求。 */
    public synchronized boolean isEndgame(Bitfield local) {
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
