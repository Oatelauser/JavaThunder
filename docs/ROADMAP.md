# JavaThunder 路线图：协议覆盖与里程碑

对外承诺的功能边界。坐标：`io.github.oatelauser:javathunder-*`，包根 `io.github.oatelauser.thunder`。
定位：供第三方项目使用的 P2P 下载库——通过正交特性开关（`builder().dht(true).pex(true).maxPeers(8)...`）
即可裁剪出轻量形态，也可开启完整形态；不提供模式枚举，"轻量/完整"是配置的两个特例。

## 第一阶段（MVP）：v1 协议核心

| BEP | 内容 | 范围 |
|---|---|---|
| BEP 3 | 核心线协议 | 握手、Bitfield、choke/unchoke、interested、request/piece/cancel、have、keep-alive |
| BEP 3 (v1) | v1 种子元数据 | Bencode 解码、info 字典、SHA-1 分片哈希、info-hash 计算 |
| BEP 12 | 多 Tracker 分层 | announce-list 逐层轮询与故障切换 |
| BEP 20 | Peer ID 命名 | 本客户端标识前缀 `-JT0001-` |
| BEP 23 | Tracker 紧凑响应 | compact=1 解析 |
| BEP 27 | 私有种子标记 | 解析并暴露 `private` 标志；阶段 2 起 DHT/PEX 遇此标志强制关闭 |

引擎能力：预分配 + Block 直写最终偏移、SHA-1 逐 Piece 校验、断点续传（`.jt-resume` 状态文件、
重启重校验）、rarest-first + 首块随机 + endgame 调度、标准 tit-for-tat choking、令牌桶限速
（全局 + 单任务、上下行独立）。

## 第二阶段（完整模式）

| BEP | 内容 | 解锁能力 |
|---|---|---|
| BEP 10 | 扩展握手 | 一切扩展的载体 |
| BEP 9 | ut_metadata 元数据交换 | 磁力链接 |
| BEP 5 | DHT（Kademlia） | 去 Tracker 化。**已实现（查询模式）**：KRPC + 路由表 + 迭代查找 + get_peers/announce_peer；不做全功能节点（不响应他人 query）。**引擎已接入（B5）**：`builder().peerDiscovery(DhtPeerDiscovery.create())` 注入，会话与磁力按 announce 周期补充候选；private 种子（BEP 27）自动禁用 |
| BEP 11 | PEX | Peer 互相发现。**已实现（B7）**：ut_pex 协商 + added/added.f 紧凑表解析 + 每 60s 广播连接表；private 种子不启用 |
| BEP 15 | UDP Tracker | Tracker 通道冗余。**已实现（B6）**：connect 60s 缓存 + announce + 事务 ID 校验 + 指数退避重试，按 URL scheme 自动分派，UDP 栈不可用时回退 HTTP |

DHT 已落为独立可选模块（`javathunder-dht`，查询模式），轻量使用者无需引入该 jar；
引擎通过 api 模块的 `PeerDiscoverySource` SPI 消费（core 不依赖 dht，方向为 dht → api）。

### 2.x 生产级 Tracker 模块（已实现：javathunder-tracker）

`javathunder-tracker`：现网可用的 tracker（内网分发的"opentracker 替代"，
纯 Java 零依赖、可执行 jar 直跑：`java -jar javathunder-tracker-*-with-dependencies.jar
--port 6881 --announce-interval 1800 [--udp-port 6881] [--whitelist <hex>|@file]`）。
已交付：TrackerServer（固定端口 / announce 间隔过期清理 / stopped 事件摘除 /
多 swarm 并发 / 可观测统计）+ UDP announce（BEP 15 服务端，默认与 HTTP 同端口，
`--udp-port 0` 关闭）+ scrape（BEP 48，downloaded 完成累计）+ info-hash 白名单 +
/stats（HTML）与 /metrics（Prometheus 文本）端点 + 保留 EmbeddedTracker
（tools 场景零迁移，包 `io.github.oatelauser.thunder.tracker`）。
选型依据见 MANUAL §1.3：内网分发自建 tracker 首选，DHT 是去中心化备选而非替代。

#### 附注：集群同步评估（多实例 A/B 互为副本，对标 opentracker live sync；只评估未决策）

- **状态模型**：需同步的状态只有两类——内存 swarm 表（info-hash → {peer 地址 →
  lastSeen/seeder}，随 announce 更新、按 interval×2 过期的短命状态）与每 swarm 的
  downloaded 完成计数（单调递增的累积状态）。前者天然幂等（同一 peer 的新 announce
  覆盖旧值，合并规则 = 按 lastSeen 取新），后者可 max-merge，冲突消解都无歧义。
- **CAP 取舍：AP**。announce 数据天然短暂过期（缺一轮 announce 即失效），客户端
  （BEP 12）会向 announce-list 里所有 tracker 逐层重试，偶发读到旧 peer 列表只是
  多一次连接失败后换源；强行 CP（每次 announce 写共享强一致存储）只会给热路径加
  延迟，换不来客户端可见的收益。eventual consistency 是正确取舍。
- **候选方案**：①实例间 UDP gossip 同步增量（opentracker live.sync 的思路：把
  announce 产生的 swarm 变更以 UDP 单播/组播发给对等实例，无外部依赖、贴合 AP）；
  ②外部 Redis 共享 swarm 表（一致性最强，但引入运维依赖与每次 announce 的额外
  RTT，违背本模块"纯 Java 零依赖"定位）；③不做同步，靠客户端多 tracker announce
  天然容灾（现状即成立：A/B 各自持有部分视图，互为备份，客户端两边都 announce
  即可获得并集；代价是单实例 scrape/stats 视图不完整）。
- **推荐结论**：默认取 ③（零成本、已可用，内网分发场景够用）；确需全局单点视图
  （统一 scrape/告警、单 URL 入口）再上 ①，估计 5–8 个工作日（变更日志缓冲 +
  对等列表配置 + lastSeen 合并 + 计数 max-merge + 双实例集成测试）；②否决。
  触发条件：出现"多实例但要求统一统计出口"的真实需求时立项，届时另立 ADR。

## 第三阶段（按需/远期）

- BEP 6 快速扩展
- BEP 19 HTTP/Web 种子（HTTP 镜像兜底下载源）
- BEP 52 v2 / 混合种子（Merkle 树 + SHA-256）

## 明确不实现

- MSE/PE 连接加密：非 BEP 标准，反限速用途，处于灰色地带
- uTP（BEP 29）：需自研拥塞控制，与目标量级不匹配
- BEP 30 Merkle hash torrent：已被 v2 取代的死标准

## 工程护栏

- 公共 API 与实现分模块（见 ADR-0002），`*.internal.*` 包永不公共
- japicmp 基线比对在第一个 0.x tag（有可比对的历史版本）后立即启用
- CI 测试矩阵：JDK 21 / 25 / 26
- 0.x 期间 API 可破坏；MVP + API 走查通过后升 1.0 冻结公共 API
