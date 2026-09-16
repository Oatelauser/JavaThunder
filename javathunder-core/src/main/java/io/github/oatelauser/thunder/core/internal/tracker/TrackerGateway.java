package io.github.oatelauser.thunder.core.internal.tracker;

import org.jspecify.annotations.Nullable;

/**
 * 发现渠道 seam（URL scheme → 客户端路由）：{@code udp://} 走 {@link UdpTrackerClient}，
 * 其余（http/https）走 HTTP {@link TrackerClient}。DownloadSession 与 MetadataFetcher
 * 共用此分派；不做重试与退避——那些策略属于调用方。
 */
public final class TrackerGateway {

    private final TrackerClient http;
    @Nullable
    private final UdpTrackerClient udp;

    public TrackerGateway(TrackerClient http, @Nullable UdpTrackerClient udp) {
        this.http = http;
        this.udp = udp;
    }

    /**
     * 按 URL scheme 分派 announce。
     *
     * @throws TrackerException 客户端侧失败原样上抛，由调用方决定退避 / tier 转移
     */
    public AnnounceResponse announce(String url, AnnounceRequest request) throws TrackerException {
        return UdpTrackerClient.supports(url) && udp != null
                ? udp.announce(url, request)
                : http.announce(url, request);
    }
}
