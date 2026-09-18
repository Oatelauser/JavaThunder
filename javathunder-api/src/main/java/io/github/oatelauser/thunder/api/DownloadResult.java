package io.github.oatelauser.thunder.api;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 下载完成结果：任务完成的判定 = 全部参与校验的 Piece 均已通过校验
 * （v1/混合为 SHA-1 逐件，v2-only 为 SHA-256 Merkle 逐件——见 MANUAL §4.8；
 * 设置了 {@link FileFilter} 时为全部必需件）。
 *
 * @param name     种子名（单文件种子即文件名，多文件种子为根目录名）
 * @param file     结果路径：单文件种子为该文件；多文件种子为根目录（种子内相对路径
 *                 从此解析）。{@code seedAfterComplete(true)} 完成的做种任务为
 *                 工作区路径（.part 形态，数据即工作对象）
 * @param bytes    完成字节数：全量下载 = 种子总长；选择性下载按必需件计
 *                 （跨界件整件计入，见 MANUAL §4.9）
 * @param elapsed  从任务启动到完成的耗时（含元数据获取与校验）
 */
public record DownloadResult(String name, Path file, long bytes, Duration elapsed) {
}
