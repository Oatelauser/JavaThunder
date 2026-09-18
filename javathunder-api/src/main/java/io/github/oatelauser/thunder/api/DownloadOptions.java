package io.github.oatelauser.thunder.api;

import java.nio.file.Path;

/**
 * 单任务选项。速率 0 = 不限。
 *
 * @param downloadLimitBytesPerSecond 任务级下载限速（与全局限速串联，两者都需放行）
 * @param uploadLimitBytesPerSecond   任务级上传限速
 * @param restartVerifyMode           断点续传的重启校验档位（默认 FULL；大镜像建议 SAMPLED）
 * @param fileFilter                  多文件种子的文件取舍（默认全量；见 {@link FileFilter}）
 * @param downloadOrder               选件顺序（默认 {@link DownloadOrder#RAREST_FIRST}）
 * @param filePriorities             文件级优先级（默认全部 {@link FilePriority#NORMAL}）
 */
public record DownloadOptions(
        Path targetDir,
        boolean resumeEnabled,
        boolean verifyOnRestart,
        boolean seedAfterComplete,
        long downloadLimitBytesPerSecond,
        long uploadLimitBytesPerSecond,
        RestartVerifyMode restartVerifyMode,
        FileFilter fileFilter,
        DownloadOrder downloadOrder,
        FilePriority filePriorities) {

    /**
     * @deprecated 用 {@link #restartVerifyMode} 三档替代布尔开关；等价于 FULL/NONE。
     */
    @Deprecated
    public DownloadOptions(Path targetDir, boolean resumeEnabled, boolean verifyOnRestart,
            boolean seedAfterComplete, long downloadLimitBytesPerSecond,
            long uploadLimitBytesPerSecond) {
        this(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadLimitBytesPerSecond, uploadLimitBytesPerSecond,
                verifyOnRestart ? RestartVerifyMode.FULL : RestartVerifyMode.NONE,
                FileFilter.all(), DownloadOrder.RAREST_FIRST, FilePriority.all(FilePriority.NORMAL));
    }

    /**
     * @deprecated 用 10 参构造（含 {@link FilePriority}）或
     * {@link #defaults()} + {@link #fileFilter(FileFilter)} /
     * {@link #downloadOrder(DownloadOrder)} 链式设置；本重载等于全量 + 稀缺优先。
     */
    @Deprecated
    public DownloadOptions(Path targetDir, boolean resumeEnabled, boolean verifyOnRestart,
            boolean seedAfterComplete, long downloadLimitBytesPerSecond,
            long uploadLimitBytesPerSecond, RestartVerifyMode restartVerifyMode) {
        this(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadLimitBytesPerSecond, uploadLimitBytesPerSecond,
                restartVerifyMode, FileFilter.all(), DownloadOrder.RAREST_FIRST,
                FilePriority.all(FilePriority.NORMAL));
    }

    /**
     * @deprecated 用 10 参构造或链式设置；本重载等于稀缺优先。
     */
    @Deprecated
    public DownloadOptions(Path targetDir, boolean resumeEnabled, boolean verifyOnRestart,
            boolean seedAfterComplete, long downloadLimitBytesPerSecond,
            long uploadLimitBytesPerSecond, RestartVerifyMode restartVerifyMode,
            FileFilter fileFilter) {
        this(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadLimitBytesPerSecond, uploadLimitBytesPerSecond,
                restartVerifyMode, fileFilter, DownloadOrder.RAREST_FIRST,
                FilePriority.all(FilePriority.NORMAL));
    }

    /**
     * @deprecated 用 10 参构造或 {@link #defaults()} + {@link #filePriorities(FilePriority)}
     * 链式设置；本重载等于全量 NORMAL 优先级。
     */
    @Deprecated
    public DownloadOptions(Path targetDir, boolean resumeEnabled, boolean verifyOnRestart,
            boolean seedAfterComplete, long downloadLimitBytesPerSecond,
            long uploadLimitBytesPerSecond, RestartVerifyMode restartVerifyMode,
            FileFilter fileFilter, DownloadOrder downloadOrder) {
        this(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadLimitBytesPerSecond, uploadLimitBytesPerSecond,
                restartVerifyMode, fileFilter, downloadOrder,
                FilePriority.all(FilePriority.NORMAL));
    }

    public static DownloadOptions defaults() {
        return new DownloadOptions(Path.of("downloads"), true, true, false,
                0, 0, RestartVerifyMode.FULL, FileFilter.all(), DownloadOrder.RAREST_FIRST,
                FilePriority.all(FilePriority.NORMAL));
    }

    public DownloadOptions targetDir(Path dir) {
        return new DownloadOptions(dir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadLimitBytesPerSecond, uploadLimitBytesPerSecond, restartVerifyMode,
                fileFilter, downloadOrder, filePriorities);
    }

    public DownloadOptions rateLimits(long downloadBytesPerSecond, long uploadBytesPerSecond) {
        return new DownloadOptions(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadBytesPerSecond, uploadBytesPerSecond, restartVerifyMode, fileFilter,
                downloadOrder, filePriorities);
    }

    /**
     * 重启校验三档快捷设置。
     */
    public DownloadOptions restartVerify(RestartVerifyMode mode) {
        return new DownloadOptions(targetDir, resumeEnabled, mode != RestartVerifyMode.NONE,
                seedAfterComplete, downloadLimitBytesPerSecond, uploadLimitBytesPerSecond, mode,
                fileFilter, downloadOrder, filePriorities);
    }

    /**
     * 选择性下载：只下载谓词放行的文件（多文件种子；单文件种子对文件名判定一次）。
     */
    public DownloadOptions fileFilter(FileFilter filter) {
        return new DownloadOptions(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadLimitBytesPerSecond, uploadLimitBytesPerSecond, restartVerifyMode, filter,
                downloadOrder, filePriorities);
    }

    /**
     * 选件顺序：默认稀缺优先；流式消费场景改 {@link DownloadOrder#SEQUENTIAL}。
     */
    public DownloadOptions downloadOrder(DownloadOrder order) {
        return new DownloadOptions(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadLimitBytesPerSecond, uploadLimitBytesPerSecond, restartVerifyMode,
                fileFilter, order, filePriorities);
    }

    /**
     * 文件级优先级：HIGH 先拉 / NORMAL 常规 / SKIP 不下（与 FileFilter 组合见
     * {@link FilePriority}）；只影响次序，不影响完成判定。
     */
    public DownloadOptions filePriorities(FilePriority priorities) {
        return new DownloadOptions(targetDir, resumeEnabled, verifyOnRestart, seedAfterComplete,
                downloadLimitBytesPerSecond, uploadLimitBytesPerSecond, restartVerifyMode,
                fileFilter, downloadOrder, priorities);
    }
}
