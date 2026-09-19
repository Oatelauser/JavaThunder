package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.peer.transport.HandshakeRouter;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.TransportHandler;
import io.github.oatelauser.thunder.core.internal.ratelimit.RateLimiter;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 件占位的跨通道互斥契约（claimPieceForPeer × claimPieceForWebSeed）：Peer 与
 * WebSeed 的条件占位合起来须覆盖全部交错序——任一件至多一个抢占者；Peer 侧补块
 * 语义（bestBusy：多会话合件在途件）不得被条件占位误伤；让位/撤回不得残留占用。
 * 选件与占位之间的真实并发竞态由验收套件（MultiPeer/WebSeed/Sequential 双通道）
 * 端到端覆盖，此处以确定性顺序钉住认领接缝本身的契约。
 */
class DownloadSessionPieceClaimTest {

    @TempDir
    Path targetDir;

    @Test
    void peerClaimBlocksWebSeedClaim() throws IOException {
        DownloadSession session = newSession();
        try {
            assertTrue(session.claimPieceForPeer(0));
            assertFalse(session.claimPieceForWebSeed(0), "Peer 已占 activePieces：WebSeed 认领须让位");
        } finally {
            session.cancel(false);
        }
    }

    @Test
    void webSeedClaimBlocksPeerClaim() throws IOException {
        DownloadSession session = newSession();
        try {
            assertTrue(session.claimPieceForWebSeed(0));
            assertFalse(session.claimPieceForPeer(0), "WebSeed 已占 verifyingPieces：Peer 占位须撤回让位");
        } finally {
            session.cancel(false);
        }
    }

    @Test
    void busyPieceJoinStaysAllowed() throws IOException {
        DownloadSession session = newSession();
        try {
            assertTrue(session.claimPieceForPeer(0));
            assertTrue(session.claimPieceForPeer(0), "补块路径（bestBusy）：在途件允许多会话合件，条件占位不得误伤");
        } finally {
            session.cancel(false);
        }
    }

    @Test
    void peerBackOffLeavesNoActiveLeak() throws IOException {
        DownloadSession session = newSession();
        try {
            assertTrue(session.claimPieceForWebSeed(0));
            assertFalse(session.claimPieceForPeer(0));
            session.releaseWebSeedClaim(0);
            assertTrue(session.claimPieceForWebSeed(0), "Peer 让位撤回须移除 activePieces 占位（残留会令本次认领失败）");
        } finally {
            session.cancel(false);
        }
    }

    @Test
    void releasedWebSeedClaimIsReclaimableByPeer() throws IOException {
        DownloadSession session = newSession();
        try {
            assertTrue(session.claimPieceForWebSeed(0));
            session.releaseWebSeedClaim(0);
            assertTrue(session.claimPieceForPeer(0), "WebSeed 释放后 Peer 可重抢");
        } finally {
            session.cancel(false);
        }
    }

    private DownloadSession newSession() throws IOException {
        TorrentMetadata meta = new TorrentMetadata(new byte[20], null, List.of(), null, null,
                null, "claim-test", 4 * 32_768L, 32_768, new byte[4 * 20], false,
                List.of(), List.of());
        DownloadSession.SessionConfig config = new DownloadSession.SessionConfig(4, 6881,
                RateLimiter.unlimited(), RateLimiter.unlimited(), null, null);
        return new DownloadSession(meta, DownloadOptions.defaults().targetDir(targetDir),
                config, new NoopTransport(), new TrackerClient(), Runnable::run, new byte[20]);
    }

    /** 连接层桩：占位契约测试不发起任何连接（空 tracker tier，announce 无网络副作用）。 */
    private static final class NoopTransport implements PeerTransport {

        @Override
        public int listen(int preferredPort, HandshakeRouter router) {
            return preferredPort;
        }

        @Override
        public void connect(InetSocketAddress address, byte[] infoHash, TransportHandler handler) {
            // 测试桩：不发起连接
        }

        @Override
        public int listeningPort() {
            return -1;
        }

        @Override
        public void close() {
        }
    }
}
