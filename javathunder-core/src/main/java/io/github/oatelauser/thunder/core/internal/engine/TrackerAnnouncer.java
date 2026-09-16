package io.github.oatelauser.thunder.core.internal.engine;

import io.github.oatelauser.thunder.core.internal.tracker.AnnounceRequest;
import io.github.oatelauser.thunder.core.internal.tracker.AnnounceResponse;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerEvent;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerException;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * tracker announce 编排（BEP 3 多 tier）：tier 内按序尝试，失败（异常或 failure
 * reason）转移同一 tier 的下一个 URL 及后续 tier；任一 tracker 成功即返回并复位
 * 退避。全部 tracker 失败时指数退避——interval×2^k，上限 30 分钟，成功一次即复位。
 * 应答中的 peer 全部交给候选队列（去重由候选消费方负责）。
 */
final class TrackerAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(TrackerAnnouncer.class);
    private static final int NUMWANT = 50;
    private static final int BACKOFF_CAP_SECONDS = 30 * 60;

    private final byte[] infoHash;
    private final byte[] peerId;
    private final int listenPort;
    private final List<List<String>> tiers;
    private final TrackerGateway gateway;
    private final TaskEventDispatcher dispatcher;
    private final LongSupplier remainingBytes;
    private final Consumer<InetSocketAddress> candidateSink;
    private final LongSupplier uploadedSupplier;
    private final LongSupplier downloadedSupplier;

    private volatile int intervalSeconds = 5;
    /**
     * 全部 tracker 失败后的指数退避基数（秒）；成功 announce 复位为 0。
     */
    private volatile int backoffSeconds;

    TrackerAnnouncer(byte[] infoHash, byte[] peerId, int listenPort, List<List<String>> tiers,
            TrackerGateway gateway, TaskEventDispatcher dispatcher, LongSupplier remainingBytes,
            Consumer<InetSocketAddress> candidateSink, LongSupplier uploadedSupplier,
            LongSupplier downloadedSupplier) {
        this.infoHash = infoHash.clone();
        this.peerId = peerId.clone();
        this.listenPort = listenPort;
        this.tiers = tiers.stream().map(List::copyOf).toList();
        this.gateway = gateway;
        this.dispatcher = dispatcher;
        this.remainingBytes = remainingBytes;
        this.candidateSink = candidateSink;
        this.uploadedSupplier = uploadedSupplier;
        this.downloadedSupplier = downloadedSupplier;
    }

    /**
     * 对全部 tier 发起一次 announce（任一 tracker 成功即返回）。事件（STARTED /
     * COMPLETED / STOPPED / NONE）语义同 BEP 3。
     */
    void announce(TrackerEvent event) {
        long left = remainingBytes.getAsLong();
        AnnounceRequest request = new AnnounceRequest(infoHash, peerId, listenPort,
                uploadedSupplier.getAsLong(), downloadedSupplier.getAsLong(), left, event, NUMWANT);
        boolean anySuccess = false;
        for (List<String> tier : tiers) {
            for (String url : tier) {
                try {
                    AnnounceResponse response = gateway.announce(url, request);
                    if (response.failureReason() != null) {
                        log.warn("tracker {} rejected announce: {}", url, response.failureReason());
                        dispatcher.trackerAnnounce(url, response.failureReason(), 0, 0);
                        continue;
                    }
                    intervalSeconds = response.interval();
                    for (InetSocketAddress peer : response.peers()) {
                        candidateSink.accept(peer);
                    }
                    dispatcher.trackerAnnounce(url, null, response.seeders(), response.leechers());
                    anySuccess = true;
                    backoffSeconds = 0; // 成功即复位
                    return;
                } catch (TrackerException e) {
                    log.debug("tracker {} failed: {}", url, e.getMessage());
                    dispatcher.trackerAnnounce(url, e.getMessage(), 0, 0);
                }
            }
        }
        log.warn("announce {} failed on all trackers", event);
        if (!anySuccess) {
            // 全部 tracker 失败：指数退避 interval×2^k，上限 30 分钟
            backoffSeconds = backoffSeconds == 0
                    ? Math.max(2, intervalSeconds) * 2
                    : Math.min(backoffSeconds * 2, BACKOFF_CAP_SECONDS);
            intervalSeconds = backoffSeconds;
        }
    }

    /** 当前 announce 间隔（秒）；tracker 应答可更新，全败退避会抬高。 */
    int intervalSeconds() {
        return intervalSeconds;
    }
}
