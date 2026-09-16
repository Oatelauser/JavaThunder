package io.github.oatelauser.thunder.api;

import java.nio.file.Path;

/**
 * 客户端门面。一个实例承载全局资源（监听端口、全局限速、事件线程），
 * 可同时运行多个下载任务。实现 {@link AutoCloseable}：close 停止全部任务并释放资源。
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

    @Override
    void close();
}
