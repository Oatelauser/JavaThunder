package io.github.oatelauser.thunder.api;

import java.nio.file.Path;

/**
 * 纯做种选项（G2：导入已有文件直接做种）。
 *
 * @param dataDir            已有数据所在目录（文件名/目录树须与种子一致——
 *                           单文件种子找 {@code dataDir/<name>}，多文件找 {@code dataDir/<name>/...}）
 * @param uploadLimitBytesPerSecond 任务级上传限速（0 = 不限；与全局串联）
 */
public record SeedOptions(Path dataDir, long uploadLimitBytesPerSecond) {

    /** 全默认：数据目录 {@code downloads}、不限速。 */
    public static SeedOptions defaults() {
        return new SeedOptions(Path.of("downloads"), 0);
    }

    /** 数据目录 wither。 */
    public SeedOptions dataDir(Path dir) {
        return new SeedOptions(dir, uploadLimitBytesPerSecond);
    }

    /** 上传限速 wither（字节/秒；0 = 不限）。 */
    public SeedOptions uploadLimitBytesPerSecond(long bytesPerSecond) {
        return new SeedOptions(dataDir, bytesPerSecond);
    }
}
