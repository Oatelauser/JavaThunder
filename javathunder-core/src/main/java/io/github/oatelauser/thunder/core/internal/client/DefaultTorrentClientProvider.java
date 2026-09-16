package io.github.oatelauser.thunder.core.internal.client;

import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.api.TorrentClientProvider;

/**
 * ServiceLoader 实现入口（{@code META-INF/services} 注册）：api 侧
 * {@link TorrentClient#create()} / {@link TorrentClient#builder()} 经此发现实现。
 */
public final class DefaultTorrentClientProvider implements TorrentClientProvider {

    @Override
    public TorrentClient.Builder newBuilder() {
        return DefaultTorrentClient.builder();
    }
}
