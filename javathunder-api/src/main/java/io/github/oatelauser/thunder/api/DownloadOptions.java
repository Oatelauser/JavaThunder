package io.github.oatelauser.thunder.api;

import java.nio.file.Path;

/**
 * 单任务选项。速率 0 = 不限。
 *
 * @param downloadLimitBytesPerSecond 任务级下载限速（与全局限速串联，两者都需放行）
 * @param uploadLimitBytesPerSecond   任务级上传限速
 */
public record DownloadOptions(
    Path targetDir,
    boolean resumeEnabled,
    boolean verifyOnRestart,
    boolean seedAfterComplete,
    long downloadLimitBytesPerSecond,
    long uploadLimitBytesPerSecond) {

    public static DownloadOptions defaults() {
        return new DownloadOptions(Path.of("downloads"), true, true, false, 0, 0);
    }

    public DownloadOptions targetDir(Path dir) {
        return new DownloadOptions(dir, resumeEnabled, verifyOnRestart, seedAfterComplete,
            downloadLimitBytesPerSecond, uploadLimitBytesPerSecond);
    }

    public DownloadOptions rateLimits(long downloadBytesPerSecond, long uploadBytesPerSecond) {
        return new DownloadOptions(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
            downloadBytesPerSecond, uploadBytesPerSecond);
    }
}
