package com.example.thunder.service;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.MagnetUri;
import io.github.oatelauser.thunder.api.TorrentClient;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务注册表 + 提交/查询。演示要点：
 *
 * <ul>
 *   <li><b>提交即返回</b>：client.download(...) 本身是非阻塞的（返回带 future 的任务句柄），
 *       所以 REST 层天然异步——POST 挂后台，立即 202。</li>
 *   <li><b>磁力 vs 种子自动判断</b>：source 以 "magnet:" 开头走 MagnetUri.parse，
 *       否则按 .torrent 文件路径处理。</li>
 *   <li>句柄放 ConcurrentHashMap；真实应用可加过期清理/持久化，这里保持最小。</li>
 * </ul>
 */
@Service
public class DownloadService {

    private final TorrentClient client;
    private final Map<UUID, DownloadTask> tasks = new ConcurrentHashMap<>();

    public DownloadService(TorrentClient client) {
        this.client = client;
    }

    /** 注册并开始下载，返回任务 ID。 */
    public UUID submit(String source, Path targetDir) throws Exception {
        UUID id = UUID.randomUUID();
        DownloadTask task;
        if (source.startsWith("magnet:")) {
            task = client.download(MagnetUri.parse(source), options(targetDir));
        } else {
            task = client.download(Path.of(source), options(targetDir));
        }
        tasks.put(id, task);
        return id;
    }

    public Optional<DownloadTask> find(UUID id) {
        return Optional.ofNullable(tasks.get(id));
    }

    /** 不存在时抛 NoSuchElementException（Controller 映射为 404）。 */
    public DownloadTask require(UUID id) {
        return find(id).orElseThrow(() -> new NoSuchElementException("download not found: " + id));
    }

    /** cancel 并注销；任务不存在返回 false。已完成的任务 cancel 是无害空操作。 */
    public boolean cancel(UUID id, boolean deleteData) {
        return find(id).map(task -> {
            task.cancel(deleteData);
            tasks.remove(id);
            return true;
        }).orElse(false);
    }

    private static DownloadOptions options(Path targetDir) {
        return DownloadOptions.defaults().targetDir(targetDir);
    }
}
