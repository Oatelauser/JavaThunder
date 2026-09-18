package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.peer.transport.PeerChannel;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单个 Peer 的会话状态。pending/issued/currentPiece 必须在本对象监视器下访问
 * （持有者线程在 synchronized(session) 块内读写）；远端分片位图不在此处——
 * 单一事实来源是 {@link PieceScheduler} 的 peers 表（peerConnected 注册）。
 */
final class PeerSession {

    final String key;
    final PeerChannel channel;
    final ArrayDeque<BlockRequest> pending = new ArrayDeque<>();
    final Set<BlockRequest> issued = ConcurrentHashMap.newKeySet();
    /**
     * 上传服务队列（单线程 FIFO 虚拟线程）：提交序 = 执行序 = 写出序。串行化是
     * 互操作正确性要求而非仅性能选择——ttorrent 1.5 的 Piece.record 在收到
     * offset=0 的块时会重置整片缓冲，若块服务乱序完成（虚拟线程并发 + 磁盘读/
     * 限速等待的完成序不确定），晚到的块 0 会静默抹掉已收块，拼出损坏件（A1
     * 互操作实测；CPU 饱和下确定性复现）。跨 Peer 仍并行（每会话各一线程）。
     */
    final ExecutorService serveExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("javathunder-serve-").factory());
    volatile boolean peerChokingUs = true;
    volatile boolean weChokingThem = true;
    volatile boolean remoteInterested;
    /**
     * 对端协商的 ut_pex 子 ID（>0 表示 PEX 已协商，按此值发送）。
     */
    volatile int remotePexId = -1;
    int currentPiece = -1;

    PeerSession(String key, PeerChannel channel) {
        this.key = key;
        this.channel = channel;
    }
}
