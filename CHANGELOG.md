# Changelog

按版本记录**用户可见**的变更；纯内部重构详录于 git 历史。坐标：`io.github.oatelauser:javathunder-*`。

## 0.5.0（开发中）

### 新增

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
