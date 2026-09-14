package io.github.oatelauser.thunder.api;

import java.nio.file.Path;

/**
 * 客户端门面。一个实例承载全局资源（监听端口、全局限速、事件线程），
 * 可同时运行多个下载任务。实现 {@link AutoCloseable}：close 停止全部任务并释放资源。
 */
public interface TorrentClient extends AutoCloseable {

    DownloadTask download(Path torrentFile, DownloadOptions options) throws Exception;

    @Override
    void close();
}
