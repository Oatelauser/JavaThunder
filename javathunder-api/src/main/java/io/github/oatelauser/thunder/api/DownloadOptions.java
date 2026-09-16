package io.github.oatelauser.thunder.api;

import java.nio.file.Path;

/**
 * 单任务选项。速率 0 = 不限。
 *
 * @param downloadLimitBytesPerSecond 任务级下载限速（与全局限速串联，两者都需放行）
 * @param uploadLimitBytesPerSecond   任务级上传限速
 * @param restartVerifyMode           断点续传的重启校验档位（默认 FULL；大镜像建议 SAMPLED）
 */
public record DownloadOptions(
        Path targetDir,
        boolean resumeEnabled,
        boolean verifyOnRestart,
        boolean seedAfterComplete,
        long downloadLimitBytesPerSecond,
        long uploadLimitBytesPerSecond,
        RestartVerifyMode restartVerifyMode) {

    /**
     * @deprecated 用 {@link #restartVerifyMode} 三档替代布尔开关；等价于 FULL/NONE。
     */
    @Deprecated
    public DownloadOptions(Path targetDir, boolean resumeEnabled, boolean verifyOnRestart,
            boolean seedAfterComplete, long downloadLimitBytesPerSecond, long uploadLimitBytesPerSecond) {
        this(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadLimitBytesPerSecond, uploadLimitBytesPerSecond,
                verifyOnRestart ? RestartVerifyMode.FULL : RestartVerifyMode.NONE);
    }

    public static DownloadOptions defaults() {
        return new DownloadOptions(Path.of("downloads"), true, true, false,
                0, 0, RestartVerifyMode.FULL);
    }

    public DownloadOptions targetDir(Path dir) {
        return new DownloadOptions(dir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadLimitBytesPerSecond, uploadLimitBytesPerSecond, restartVerifyMode);
    }

    public DownloadOptions rateLimits(long downloadBytesPerSecond, long uploadBytesPerSecond) {
        return new DownloadOptions(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadBytesPerSecond, uploadBytesPerSecond, restartVerifyMode);
    }

    /**
     * 重启校验三档快捷设置。
     */
    public DownloadOptions restartVerify(RestartVerifyMode mode) {
        return new DownloadOptions(targetDir, resumeEnabled, mode != RestartVerifyMode.NONE,
                seedAfterComplete, downloadLimitBytesPerSecond, uploadLimitBytesPerSecond, mode);
    }

}
