package io.github.oatelauser.thunder.core.internal.tracker;

import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * announce 响应。{@code failureReason} 非 null 表示 tracker 拒绝了本次 announce
 * （调用方决定退避/换 tier），此时 peers 为空。
 */
public record AnnounceResponse(
    int interval,
    int seeders,
    int leechers,
    List<InetSocketAddress> peers,
    @Nullable String failureReason) {

    public AnnounceResponse {
        peers = List.copyOf(peers);
    }
}
