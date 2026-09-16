package io.github.oatelauser.thunder.api;

/**
 * 实现发现入口（ServiceLoader）：实现模块（javathunder-core）提供本接口的实现并在
 * {@code META-INF/services} 注册，api 侧 {@link TorrentClient#create()} /
 * {@link TorrentClient#builder()} 据此发现实现——api 不依赖任何实现模块。
 */
public interface TorrentClientProvider {

    /**
     * 返回实现侧的配置构建器。
     */
    TorrentClient.Builder newBuilder();
}
