package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.webseed.HttpRangeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP 兜底源拉取通道（BEP 19）：与 Peer 通道平行的整件下载循环——按 availability
 * 升序挑选"本地缺失且未被任何通道占用"的件，经 {@link HttpRangeClient} 整件拉取、
 * 复用会话的既有校验/落盘路径落定。件在拉取前进 verifying 集合（对 Peer 选件隐藏），
 * 两通道互不重复；失败即放认领，Peer 照常兜底。内存上界 = 1 件在途（远低于 Peer
 * 组装器上限）；速率与 Peer 通道共享同一对两级令牌桶。
 *
 * <p>停通道条件：全部 HTTP 源熔断、连续 2 个坏件（源数据不可信）、会话
 * pause/cancel/fail/complete 或线程中断。通道停止只影响自身——Peer 通道不受影响。
 */
final class WebSeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(WebSeedFetcher.class);
    private static final int MAX_BAD_PIECES = 2;

    private final TorrentMetadata meta;
    private final HttpRangeClient client;
    private final DownloadSession session;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private int consecutiveBadPieces;

    WebSeedFetcher(TorrentMetadata meta, HttpRangeClient client, DownloadSession session) {
        this.meta = meta;
        this.client = client;
        this.session = session;
    }

    /** 启动（或 pause/resume 后重启）拉取循环；虚拟线程承载，可反复调用。 */
    void start() {
        stopped.set(false);
        Thread.ofVirtual().name("javathunder-webseed").start(() -> {
            try {
                loop();
            } catch (Throwable t) {
                log.warn("web seed loop terminated unexpectedly: {}", t.toString());
            }
        });
    }

    /** 停止当前循环（pause/cancel/fail/complete 或通道死亡）。 */
    void stop() {
        stopped.set(true);
    }

    private void loop() {
        while (!stopped.get() && session.webSeedActive()) {
            int piece = pickPiece();
            if (piece < 0) {
                if (sleepMillis(500)) {
                    return;
                }
                continue;
            }
            fetchOne(piece);
        }
    }

    /** 选件资格：本地缺失、未被任一通道占用（Peer 组装/在途/校验中）且属于必需件。 */
    private boolean claimable(int piece) {
        return !session.hasPiece(piece) && !session.pieceClaimed(piece) && session.wantedPiece(piece);
    }

    /** 选件：本地缺失 ∩ 未被占用 ∩ 必需；顺序模式（优先级降序, 索引升序），否则（优先级降序, availability 升序）——与 Peer 选件同字典序。 */
    private int pickPiece() {
        if (session.sequentialDownload()) {
            int best = -1;
            int bestPriority = Integer.MIN_VALUE;
            for (int i = 0; i < meta.pieceCount(); i++) {
                if (!claimable(i)) {
                    continue;
                }
                int priority = session.piecePriority(i);
                if (priority > bestPriority) {
                    best = i;
                    bestPriority = priority;
                }
            }
            return best;
        }
        int best = -1;
        int bestPriority = Integer.MIN_VALUE;
        int bestAvailability = Integer.MAX_VALUE;
        for (int i = 0; i < meta.pieceCount(); i++) {
            if (!claimable(i)) {
                continue;
            }
            int priority = session.piecePriority(i);
            int availability = session.pieceAvailability(i);
            if (priority > bestPriority
                    || priority == bestPriority && availability < bestAvailability) {
                best = i;
                bestPriority = priority;
                bestAvailability = availability;
            }
        }
        return best;
    }

    /** 拉取一件：先原子认领（配对约束见 {@link DownloadSession#claimPieceForWebSeed}），失败即让出。 */
    private void fetchOne(int piece) {
        if (!session.claimPieceForWebSeed(piece)) {
            return; // 认领失败 = 占用方先到（Peer 或另一 WebSeed 循环）：让出且绝不 release
        }
        byte[] body;
        try {
            body = fetchPieceBytes(piece);
            session.webSeedDownloaded(body.length);
        } catch (IOException e) {
            session.releaseWebSeedClaim(piece); // 让 Peer 通道接手该件
            if (client.allSourcesDisabled()) {
                log.info("web seed channel exhausted (all sources disabled); peer channel continues");
                stop();
            } else if (sleepMillis(1_000)) {
                stop();
            }
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.releaseWebSeedClaim(piece);
            stop();
            return;
        }
        try {
            if (session.verifyAndStoreWebSeedPiece(piece, body)) {
                consecutiveBadPieces = 0;
            } else {
                onBadPiece(piece);
            }
        } catch (IOException e) {
            session.releaseWebSeedClaim(piece);
            session.failTask(e); // 存储故障与 Peer 路径同判：任务失败
            stop();
        }
    }

    /**
     * 拉取一件的全部字节：单文件种子走拼接流整件 Range（URL 即文件）；多文件种子
     * （BEP 19 目录形态）按件 × 文件交集逐段拉取——URL = base + 种子内相对路径，
     * Range 为文件内偏移。v1 的件可跨文件（多段拼接）；v2 实文件按件对齐（单段）。
     * BEP 47 填充文件是流内全零段，本地合成零（不发 HTTP）。
     */
    private byte[] fetchPieceBytes(int piece) throws IOException {
        long pieceStart = (long) piece * meta.pieceLength();
        long pieceEnd = Math.min(pieceStart + meta.pieceLength(), meta.length());
        if (meta.files().isEmpty()) {
            return client.fetchPiece(piece, meta.pieceLength());
        }
        byte[] body = new byte[(int) (pieceEnd - pieceStart)];
        for (TorrentMetadata.TorrentFile file : meta.files()) {
            long fileStart = file.offset();
            long fileEnd = file.offset() + file.length();
            long from = Math.max(pieceStart, fileStart);
            long to = Math.min(pieceEnd, fileEnd);
            if (from >= to) {
                continue; // 该文件与件无交集（files 按偏移升序，件尾之后的文件同样落在此分支）
            }
            if (file.padding()) {
                continue; // 全零段：body 已零初始化
            }
            byte[] segment = client.fetchFileSegment(String.join("/", file.path()),
                    from - fileStart, to - fileStart - 1);
            System.arraycopy(segment, 0, body, (int) (from - pieceStart), segment.length);
        }
        return body;
    }

    /** 坏件：放认领允许重下；连续 2 件即视为源数据不可信，停通道（Peer 照常）。 */
    private void onBadPiece(int piece) {
        session.releaseWebSeedClaim(piece);
        consecutiveBadPieces++;
        log.warn("web seed piece {} failed verification (bad #{})", piece, consecutiveBadPieces);
        if (consecutiveBadPieces >= MAX_BAD_PIECES) {
            log.warn("web seed data untrustworthy after {} bad pieces; channel stopped", MAX_BAD_PIECES);
            stop();
        }
    }

    private static boolean sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
