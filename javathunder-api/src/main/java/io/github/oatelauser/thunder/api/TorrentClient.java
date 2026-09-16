package io.github.oatelauser.thunder.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;

/**
 * 客户端门面。一个实例承载全局资源（监听端口、全局限速、事件线程），
 * 可同时运行多个下载任务。实现 {@link AutoCloseable}：close 停止全部任务并释放资源。
 *
 * <p>入口：{@link #create()} / {@link #builder()}——实现经 ServiceLoader 从 classpath
 * 发现（即 javathunder-core），消费者只需依赖本 api 模块，不必触碰实现包。
 */
public interface TorrentClient extends AutoCloseable {

    DownloadTask download(Path torrentFile, DownloadOptions options) throws Exception;

    /**
     * 磁力链接下载（B1）：BEP 9 从 Peer 拉取元数据（info-hash 自校验）后转入正常下载。
     * 需要至少一个可用 tracker（或后续阶段的 DHT）与至少一个持有元数据的 Peer。
     */
    DownloadTask download(MagnetUri magnet, DownloadOptions options) throws Exception;

    /**
     * 导入已有文件直接做种（G2）：对 {@code options.dataDir()} 下的数据全量校验，
     * 全部通过即进入 SEEDING（不经历下载）；任何分片校验失败则任务 FAILED——
     * 数据不完整时应改用 {@link #download(Path, DownloadOptions)} 让引擎补缺。
     * 做种任务通过返回句柄的 {@code future()} 不会完成（SEEDING 持续），
     * 用 {@code cancel(false)}/{@code close()} 停止。
     */
    DownloadTask seed(Path torrentFile, SeedOptions options) throws Exception;

    /**
     * 停止全部任务并释放资源（监听端口、事件线程、发现源）。
     */
    @Override
    void close();

    /**
     * 全默认配置创建客户端。
     */
    static TorrentClient create() throws IOException {
        return builder().build();
    }

    /**
     * 配置构建入口：全局资源（端口 / 全局限速 / 事件线程）与任务上限。
     *
     * @throws IllegalStateException classpath 上没有实现（需引入 javathunder-core）
     */
    static Builder builder() {
        return ServiceLoader.load(TorrentClientProvider.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no TorrentClient implementation on classpath; add javathunder-core"))
                .newBuilder();
    }

    /**
     * Peer 传输实现选择（ADR-0003 的双实现差分验收）：NIO 为事件循环生产路径
     * （缺省）；BLOCKING 为阻塞参照实现（可执行规格，调试与差分对拍用）。
     */
    enum Transport {
        NIO, BLOCKING
    }

    /**
     * 客户端配置面：见 {@link #builder()}。
     */
    interface Builder {

        /**
         * Peer 监听端口。
         */
        Builder listenPort(int port);

        /**
         * 全局并发任务上限（超出则排队）。
         */
        Builder maxConcurrentTasks(int max);

        /**
         * 单任务的 Peer 连接上限。
         */
        Builder maxPeersPerTask(int max);

        /**
         * 全局下载限速（字节/秒；≤0 不限）。
         */
        Builder downloadLimitBytesPerSecond(long bytesPerSecond);

        /**
         * 全局上传限速（字节/秒；≤0 不限）。
         */
        Builder uploadLimitBytesPerSecond(long bytesPerSecond);

        /**
         * 注入监听器回调线程；缺省为库内单线程守护线程。
         */
        Builder listenerExecutor(Executor executor);

        /**
         * 注入去中心化 Peer 发现源（如 javathunder-dht 的 DhtPeerDiscovery）；
         * 生命周期归本 client：close 时一并关闭。未注入则仅 tracker 发现。
         */
        Builder peerDiscovery(PeerDiscoverySource source);

        /**
         * 传输实现选择；缺省 NIO（ADR-0003 生产路径），BLOCKING 为阻塞参照实现。
         */
        Builder transport(Transport transport);

        /**
         * 创建客户端（绑定监听端口、启动事件线程）。
         */
        TorrentClient build() throws IOException;
    }
}
