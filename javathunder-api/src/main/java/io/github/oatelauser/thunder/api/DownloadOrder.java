package io.github.oatelauser.thunder.api;

/**
 * 下载选件顺序（{@link DownloadOptions#downloadOrder}）。
 *
 * <p>顺序模式面向流式消费：按 Piece 索引从低到高落件（首文件最先凑齐，可边下边用），
 * 组装中的在途件天然最优先（其位图位未落定且仍有缺失块）。代价是放弃稀缺优先的
 * swarm 健康性——只建议在确实需要按序消费时开启。两模式完成判定与进度语义完全
 * 相同（区别只在选件顺序）。
 */
public enum DownloadOrder {
    /** 稀缺优先（默认）：优先拉持有 Peer 最少的件，保护 swarm 健康性。 */
    RAREST_FIRST,
    /** 顺序：按索引从低到高，面向流式消费（与选择性下载组合即"边下边看"）。 */
    SEQUENTIAL
}
