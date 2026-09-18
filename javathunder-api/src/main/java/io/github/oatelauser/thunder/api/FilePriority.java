package io.github.oatelauser.thunder.api;

import java.util.List;

/**
 * 多文件种子的文件级下载优先级（{@link DownloadOptions#filePriorities}）：返回值越大
 * 越先下载；{@link #SKIP}（0）= 不下载该文件。与 {@link FileFilter}（二元取舍）组合：
 * 过滤器先决定取舍，优先级在保留集内决定次序——一件压住多个文件时取其中最高优先级
 * （跨界件必须整件下载，取高者保证不拖慢高优先进度）。
 *
 * <p>建议只用三个语义档位：{@link #HIGH}（先拉）、{@link #NORMAL}（默认）、
 * {@link #SKIP}（不下）；中间值可用（数值越大越先），但档位越多调度越接近串行。
 * 优先级只影响下载次序，不影响完成判定（全部非 SKIP 件齐 = 完成）。
 */
@FunctionalInterface
public interface FilePriority {

    /** 不下载该文件（与 FileFilter 排除等效；两者同时设置时取交集语义）。 */
    int SKIP = 0;
    /** 常规优先级（默认——不设 filePriorities 时的全量行为）。 */
    int NORMAL = 1;
    /** 高优先级（在常规件之前调度）。 */
    int HIGH = 2;

    /** 该文件的下载优先级。 */
    int priority(List<String> path);

    /** 全部文件同一优先级的快捷工厂（如 {@code FilePriority.all(FilePriority.HIGH)}）。 */
    static FilePriority all(int level) {
        return path -> level;
    }
}
