package io.github.oatelauser.thunder.api;

/**
 * 断点续传的重启校验档位（DESIGN §7"抽样续传校验"的落地）。
 *
 * <ul>
 *   <li>{@link #FULL}：逐件 SHA-1 重校验（默认，最严格；TB 级镜像代价可观）</li>
 *   <li>{@link #SAMPLED}：随机抽取约 10% 已完成件校验 + 边界件（首/末件）必查——
 *       大体积镜像的推荐档位：局部磁盘损坏的检出概率随抽样比例指数上升，
 *       而坏件即使漏检，做种时被对端拒绝后仍会走坏件重下路径自愈</li>
 *   <li>{@link #NONE}：完全信任 resume 位图（最快；仅适合确信无外部改动的场景）</li>
 * </ul>
 */
public enum RestartVerifyMode {
    FULL,
    SAMPLED,
    NONE
}
