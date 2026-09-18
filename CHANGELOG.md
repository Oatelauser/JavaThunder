# Changelog

按版本记录**用户可见**的变更；纯内部重构详录于 git 历史。坐标：`io.github.oatelauser:javathunder-*`。

## 0.7.0（2026-09-18）

### 新增

- **顺序下载（`DownloadOrder.SEQUENTIAL`）**：选件按 Piece 索引从低到高（默认仍
  稀缺优先），首文件最先凑齐——与选择性下载组合即"边下边看"；组装中的在途件
  天然最优先，WebSeed 通道同序；完成判定/进度语义不变。枚举式 API 为后续的
  优先级分层预留扩展位
- **选择性下载（多文件种子的文件级取舍）**：`DownloadOptions.fileFilter(FileFilter)`
  ——只要部分文件时，其余文件不请求不校验，进度/ETA/tracker 剩余量/结果字节数全部按
  必需件换算；`FileFilter.paths(...)` 精确路径 / `FileFilter.extensions(...)` 扩展名 /
  任意 lambda 谓词三种表达。磁力路径同样适用（元数据就绪后求值）。跨界件整件下载
  （v1 拼接流粒度所限，与主流客户端一致）；换过滤器重启安全；纯做种忽略过滤器。
  该 API 在 1.0 冻结前落定。
- **修复：任务完成后状态可能被收尾异常改写**：完成/取消后后台循环的晚期异常
  （如事件线程池关闭后的拒绝）此前会走失败路径——ERROR 日志、状态从终态回退、
  二次异常外溢。现 `fail()` 对已终态任务静默降级为 debug 日志（状态机不允许
  从终态回退）。由顺序下载验收测试的时序暴露
- **磁力链接的 v2 闭环（BEP 52 哈希交换 + hybrid 磁力）**：
  - **v2-only 磁力（`urn:btmh`）完整下载**：元数据两段式——BEP 9 拉回 info 字典
    （pieces root 齐备但无层带）后，新增第二段经 BEP 52 hash request/hashes
    （消息 ID 21-23）向 Peer 按 512 对齐块拉取 piece layers，每块附带到
    pieces root 的 Merkle 证明、验证通过才装配，整带收齐再折叠终检；之后走与
    .torrent 完全相同的 v2 下载校验路径
  - **hybrid 磁力（v1 btih）完整下载**：磁力路径拉回的 hybrid 元数据（info 字典
    内含 v1 pieces）直接走 v1 面校验，无需层带
  - **做种侧对等服务 hash request**：从自有层带供出哈希与证明（越尾段以零链
    填充常数补齐）；形状不合法/不可服务回 hash reject
  - 握手新增 BEP 52 协议位声明（reserved[7] & 0x10，与 libtorrent 一致），
    哈希交换仅对双方都声明的连接使用
- **修正 Merkle 填充约定（互操作）**：非 2 的幂哈希树折叠改用与 libtorrent 相同的
  逐层 pad 链（pad₀ = 零哈希，padₖ = SHA-256(padₖ₋₁‖padₖ₋₁)）——piece 层折叠以
  pad[log2(每件块数)] 起补。旧约定（逐层固定补零）在 piece length > 16KiB 且件数
  非 2 的幂时会产生 libtorrent 不认的 pieces root；16KiB piece 下两约定等价
  （既有行为不变）

## 0.6.0（2026-09-17）

### 新增

- **BitTorrent v2 / 混合种子（BEP 52）**：
  - v2-only 种子（`meta-version=2` + `file tree` + `piece layers`）完整解析与下载，
    SHA-256 Merkle 逐件校验（16KiB 块叶子哈希 → 补零折叠 → 层带条目常时比对）
  - hybrid 种子（同一种子内 v1 SHA-1 与 v2 SHA-256 并存）自动走 v1 面校验
    （兼容性最广），v2 副哈希保留
  - `urn:btmh:1220<64hex>` 磁力解析（multihash 校验 + 截断 DHT target）；
    v2 磁力完整下载闭环顺延至 v2 Swarm 接入（0.7+）
  - BEP 47 填充文件占位但不落盘；v2 piece length 必须 2 的幂 ≥ 16KiB
  - 篡改层带 / 实文件未对齐 / 非法 piece length / 非法 meta-version 在解析期即拒绝
- **BEP 6 快速扩展**：握手协商（reserved[5] & 0x04）；做种/全量持有侧对协商对端以
  HaveAll 单帧替代整幅位图（大种子省数十 KB/连接）、空持有侧显式 HaveNone；拒绝供给
  （choke 中/未持有）回 RejectRequest 替代沉默，对端立即回收在途槽位；SuggestPiece/
  AllowedFast 解码容忍、策略不采纳。未协商对端（如 ttorrent）自动保持 BEP 3 语义。
- **WebSeed HTTP 兜底源（BEP 19，v1 单文件）**：种子的顶层 `url-list` 自动启用一条与
  Peer 通道平行的 HTTP 下载通道——整件 Range 拉取、多源轮询、源级指数退避与熔断
  （200 忽略 Range / 416 数据不符立即弃源）、坏件连续 2 件停通道、与 Peer 通道在途
  互斥不重复拉取、共享两级限速桶。无 tracker 的 url-list-only 种子现在也可解析下载。
  testkit `TorrentGenerator` 新增带 `url-list` 的造种重载（`announceUrl` 可为 null）。
  多文件 WebSeed（BEP 53，草案）暂不支持。

## 0.4.0

### 行为变更

- **默认 Peer 传输翻转为 NIO 事件循环**（对齐 ADR-0003 生产路径定位；回环吞吐约为阻塞
  参照实现的 3×）。对外协议与 API 不变；保持旧行为：
  `builder().transport(TorrentClient.Transport.BLOCKING)` 或
  `-Djavathunder.transport=blocking`。
- **磁力元数据阶段 announce 对齐下载会话语义**：tier 失败转移 + 全败指数退避
  （interval×2^k 上限 30 分钟、任一成功即复位），60 秒总窗口内周期重试；
  DHT 与 tracker 候选统一过滤自连回声。

### 新增

- api：`TorrentClient.create()` / `builder()` 静态工厂（ServiceLoader 发现实现）、嵌套
  `Builder` 接口与 `Transport` 枚举、`TorrentClientProvider` SPI——消费者只需依赖 api
  模块，不再触碰实现包（CLI、Spring 示例与全部验收测试已迁移）。
- dht：`DhtPeerDiscovery.knownNodes()` 健康度观测。
- 工程：checkstyle 全仓门禁（禁内联全路径类名 / 禁通配 import / 方法 ≤50 行，
  `config/checkstyle`）；CI 双传输差分臂显式回归。

### 移除（0.x 政策；japicmp 护栏 excludes 豁免并在此列明）

- tracker：`TrackerServer`——`EmbeddedTracker` 的 1:1 纯转发壳。替代：
  `EmbeddedTracker.start(InetAddress, port, interval)`（生产形态同一实现，
  通配地址 + 默认 6881/1800s）。
- tools：`Transports.fromSystemProperty()`——返回类型泄漏 core 内部类型。替代：
  `Transports.select()` + api 级 `Builder.transport(Transport)`。
- dht：`DhtPeerSource`——与 `DhtPeerDiscovery` 平行的同名能力入口。替代：
  `DhtPeerDiscovery.create()` / `create(bootstrapNodes)`（构造即自举）。

### 修复

- 多文件种子：写路径跨越**流中段 0 字节文件**不再 NPE（scatterWrite 跳过零长段，
  含回归用例；读路径本有守卫）。
- 磁力 announce 与线协议握手改用同一 BEP 20 peer id（此前两侧各生成一个）。

### 内部（详录 git 历史）

- 架构整改四阶段：DownloadSession 1359→1020 行拆为编排根 + 10 个职责单一协作者
  （事件扇出/announce 编排/PEX/校验落盘/断点存储/统计等）；DhtClient 拆 KrpcRpc +
  Frontier（消除事务 ID 自解析 hack 与每调用 executor 泄漏）；生产选件策略归一
  `PieceScheduler.pickFor`（死接口 pick/isEndgame 移除）；远端位图单源；紧凑 peer
  编解码归一 `wire/CompactPeer`；testkit 三 seeder 归一 `SeederCore`；内联全路径类名
  与超长方法全仓清零；直测补齐（RoutingTable/MultiFileStorage/BlockingTransport，
  测试总数 127→176）。
