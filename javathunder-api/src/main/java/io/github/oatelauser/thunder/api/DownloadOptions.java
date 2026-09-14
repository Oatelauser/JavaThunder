package io.github.oatelauser.thunder.api;

import java.nio.file.Path;

/** 单任务选项。 */
public record DownloadOptions(
    Path targetDir,
    boolean resumeEnabled,
    boolean verifyOnRestart,
    boolean seedAfterComplete) {

    public static DownloadOptions defaults() {
        return new DownloadOptions(Path.of("downloads"), true, true, false);
    }

    public DownloadOptions targetDir(Path dir) {
        return new DownloadOptions(dir, resumeEnabled, verifyOnRestart, seedAfterComplete);
    }
}
