package io.github.oatelauser.thunder.api;

import java.nio.file.Path;
import java.time.Duration;

/** 下载完成结果：每个 Piece 均已通过 SHA-1 校验。 */
public record DownloadResult(String name, Path file, long bytes, Duration elapsed) {
}
