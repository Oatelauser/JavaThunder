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
| BEP 5 | DHT（Kademlia） | 去 Tracker 化。**已实现（查询模式）**：KRPC + 路由表 + 迭代查找 + get_peers/announce_peer；不做全功能节点（不响应他人 query），引擎接入（磁力无 tracker 时自动用 DHT）留待后续 |
| BEP 11 | PEX | Peer 互相发现 |
| BEP 15 | UDP Tracker | Tracker 通道冗余 |

DHT 已落为独立可选模块（`javathunder-dht`，查询模式），轻量使用者无需引入该 jar。

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
