# JavaThunder 需求设计文档

| | |
|---|---|
| 版本 | 0.1（与 0.1.0-SNAPSHOT 对应） |
| 日期 | 2026-09-14 |
| 状态 | 已评审共识稿（grilling 会话产出） |
| 配套文档 | [CONTEXT.md](../CONTEXT.md)（术语表）· [ROADMAP.md](./ROADMAP.md)（协议覆盖边界）· [ADR-0001](./adr/0001-virtual-threads-over-nio.md) · [ADR-0002](./adr/0002-api-module-split.md) |

术语以 [CONTEXT.md](../CONTEXT.md) 为准：**Piece** 是登记 SHA-1 的校验单位，**Block** 是 16 KiB 的传输单位，二者在本设计中严格区分。

---

## 1. 项目概述

### 1.1 定位

JavaThunder 是一个**供第三方项目依赖的 BitTorrent 下载库**，不是终端用户应用。坐标
`io.github.oatelauser:javathunder-*`，包根 `io.github.oatelauser.thunder`，Apache-2.0 协议。

两种使用形态都由**正交特性开关**表达（不设模式枚举，"轻量/完整"只是配置的两个特例）：

- **轻量**：只拉 HTTP Tracker、少量 Peer、下完即停（默认值即偏轻量）；
- **完整**：开 DHT / PEX / 磁力链接 / UDP Tracker / 持续做种（阶段 2 起可用）。

### 1.2 目标用户与场景

- 在 JVM 服务里需要拉取大文件（数据集、模型、发行版镜像）的应用；
- 需要自建 P2P 分发能力的工具链（内部镜像分发、CDN 回源兜底）；
- 学习/研究 BitTorrent 协议的开发者（源码可读性是一等目标）。

### 1.3 一版验收标准

1. **回环实验室**：testkit 内嵌 Tracker + 种子生成器 + seeder，完整下载 100MB 随机数据，SHA-256 与源文件一致；
2. **互操作**：aria2c 做种 → JavaThunder 下载完整；JavaThunder 做种 → aria2c 下载完整；
3. **断点续传**：下载中途强杀进程，重启后续传，最终哈希一致；
4. **公网**：从 Ubuntu 官方 .torrent 下载 ISO 成功且校验通过；
5. 单元测试全绿，CI 在 JDK 21/25/26 三档矩阵通过。

### 1.4 非目标（明确不做）

- MSE/PE 连接加密、uTP（BEP 29）、BEP 30（见 ROADMAP"明确不实现"）；
- 内容搜索/发现、DHT 爬虫、任意网站资源嗅探（迅雷式离线下载不在范围）；
- 内置 GUI——可视化需求由事件 API + CLI 示例承接；
- BitTorrent v2 专属特性（第三阶段再评估）。

---

## 2. 总体架构

### 2.1 模块与依赖

```
javathunder-cli ──────▶ javathunder-core ──────▶ javathunder-api
                         （实现：bencode、        （纯接口，仅依赖
                           tracker、peer、         JSpecify 注解）
                           存储、调度、限速）
javathunder-testkit ──▶ core（内嵌 Tracker / 种子生成器 / 假 Peer）

阶段 2 新增：javathunder-dht ──▶ core（可选依赖，轻量用户不引入）
```

api 模块在编译期阻断实现类型进入公共签名（见 ADR-0002）；`*.internal.*` 包永不公共。

### 2.2 技术底座与关键决策

| 决策 | 理由 | 详见 |
|---|---|---|
| JDK 21 编译目标，运行矩阵 21/25/26 | 覆盖当前 LTS 与用户所述部署目标 | — |
| 虚拟线程 + 阻塞 Socket，每 Peer 一线程 | 数百连接量级下代码最朴素；不引入框架依赖 | ADR-0001 |
| 运行时依赖仅 `slf4j-api` | 库的依赖是转嫁给使用者的税；Bencode/SHA-1/位图/限速全手写 | — |
| Maven 多模块 + JSpecify 可空标注 | API 纪律从第一天编译期强制 | ADR-0002 |
| 0.x 期间 API 可破坏；MVP + API 走查后 1.0 冻结 | 先跑通再承诺 | ROADMAP |

### 2.3 运行视图：一条下载任务的组件协作

```
TorrentClient（门面，持有全局资源：限速器、事件线程、监听端口）
   └─ DownloadTask（每个种子一个；状态机 + Resume 状态文件）
        ├─ TrackerClient        HTTP announce，产出候选 Peer 地址
        ├─ PeerManager          连接池、握手、Bitfield 聚合、choking 决策
        │    └─ PeerConnection  每个 Peer 一条虚拟线程（阻塞 Socket）
        ├─ PieceScheduler       rarest-first 选块、请求管线、endgame
        ├─ StorageManager       预分配、Block 直写、Piece 读回校验
        └─ EventBus             监听器回调统一投递到事件线程
```

---

## 3. 公共 API 设计（跨阶段稳定）

### 3.1 使用示例

```java
try (TorrentClient client = TorrentClient.builder()
        .listenPort(6881)
        .maxConcurrentTasks(3)
        .maxPeersPerTask(50)
        .uploadRateLimit(Rate.ofMbps(10))
        .build()) {

    DownloadTask task = client.download(
        Path.of("ubuntu.torrent"),
        DownloadOptions.defaults().targetDir(Path.of("downloads")));

    task.addListener(TaskListener.onProgress(p ->
        System.out.printf("%.1f%%  ↓ %s/s  peers=%d  health=%.1f%n",
            p.fraction() * 100, p.downloadRate(), p.connectedPeers(), p.availability())));

    task.future().thenApply(DownloadResult::verifiedHash).join();
    task.pause();
    task.resume();
    task.cancel(CancelMode.KEEP_DATA);   // 或 DELETE_DATA
}
```

### 3.2 `TorrentClient` Builder 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `listenPort` | 6881 | 入站 Peer 连接监听端口 |
| `maxConcurrentTasks` | 3 | 全局最大同时下载任务数（其余排队 QUEUED） |
| `maxPeersPerTask` | 50 | 单任务最大连接 Peer 数 |
| `downloadLimitBytesPerSecond` / `uploadLimitBytesPerSecond` | 0（不限） | 全局令牌桶，上下行独立 |
| `DownloadOptions.rateLimits(dl, ul)`（每任务） | 0（不限） | 任务级令牌桶，与全局桶串联（两级都需放行，取更慢者） |
| `seedAfterComplete` | false | 完成后转 SEEDING 持续上传，否则转 COMPLETED |
| `connectTimeout` | 10s | Peer TCP 连接/握手超时 |
| `listenerExecutor` | 内置单线程事件线程 | 监听器回调投递线程，可注入 |
| 阶段 2：`dht` / `pex` / `useUdpTracker` | true | 正交开关；`private` 种子强制关闭 |

`DownloadOptions`（每任务）：`targetDir`、`resumeEnabled`（默认 true）、
`verifyOnRestart`（默认 true）、`seedAfterComplete`、`rateLimits(dl, ul)`（0 = 不限，
wither 风格，见上表）。

### 3.3 `DownloadTask` 状态机

```
QUEUED ──▶ VERIFYING ──▶ DOWNLOADING ──▶ SEEDING（seedAfterComplete=true）
（排队）    （重启重校验）    ▲    │        └─▶ COMPLETED（=false 时终态）
                 │             └────┘
                 └── 任意态可进 PAUSED（暂停）；PAUSED 可回原状态
                 └── 任意态可进 FAILED（出错，终态）；CANCELLED（删除，终态）
```

操作：`pause()` / `resume()` / `cancel(KEEP_DATA | DELETE_DATA)` / `future()` /
`addListener()` / `snapshot()`（当前 `ProgressSnapshot`）。所有阻塞操作可中断（响应
`Thread.interrupt()`）。

### 3.4 事件模型

监听器接口（非 Flow/响应式——进度事件是尽力广播，背压是伪需求）：

| 事件 | 载荷要点 |
|---|---|
| `onStateChanged` | 前态、现态、原因 |
| `onProgress` | 当前 `ProgressSnapshot`（字段见下） |
| `onPieceComplete` | piece 序号（进度位图可由此构建，UI 绿/蓝分块展示由使用者实现） |
| `onTrackerAnnounce` | tracker URL、失败原因（null = 成功）、seeders/leechers |
| `onPeerConnected` / `onPeerDisconnected` | 对端地址；断开附带原因（可 null） |
| `onError` | 阶段、异常、任务是否因此终止 |

`ProgressSnapshot` 字段：`fraction`、`downloadedBytes`/`uploadedBytes`、
`downloadRateBps`/`uploadRateBps`（EMA 平滑，α=0.3，500ms 一档）、`connectedPeers`、
`availability`、`etaMillis`（剩余字节 ÷ 平滑下行速率；速率为 0 或已完成时 null）。

回调契约：默认投递到内置单线程事件线程（或注入的 Executor）；回调抛出的异常被吞并记日志，
绝不影响协议线程；事件顺序不保证；`onProgress` 节流至 500ms 一档。

### 3.5 线程与生命周期契约

1. `TorrentClient implements AutoCloseable`：`close()` 优雅停止全部任务、断开全部 Peer，幂等、可超时；
2. 库内线程全部 daemon，命名前缀 `javathunder-*`，JVM 退出不被库挂住；
3. 监听器回调永不运行在 Peer 协议线程上；
4. 用户可见的阻塞方法全部可中断。

### 3.6 API 稳定性

- 0.x：可破坏，但破坏性变更需在 CHANGELOG 标注；
- 首个 0.x tag 后启用 japicmp 基线比对，CI 拦截未声明的破坏性变更；
- 1.0：公共 API 冻结。

---

## 4. 阶段总览

| 阶段 | 主题 | BEP | 里程碑 |
|---|---|---|---|
| 一 | v1 协议核心 + 完整引擎（MVP） | 3、12、20、23、27 | 回环/互操作/公网验收全过 |
| 二 | 完整模式（去中心化与元数据扩展） | 10、9、5、11、15 | 磁力链接可下载、无 Tracker 可下载 |
| 三 | 按需增强 | 6、19、52 | 视生态需求逐项评估 |

---

## 5. 第一阶段（MVP）详细设计

### 5.1 Bencode 编解码器（core.internal.bencode）

四种类型：整数 `i123e`、字节串 `5:hello`、列表 `l…e`、字典 `d…e`。

解码规则与防护：

- 整数禁止前导零（`i01e` 非法）、禁止 `i-0e`、禁止空数字体；
- 长度前缀同样禁止前导零，上限 16MB/串；
- 字典键必须是字节串；**解码容忍未排序键**（现实世界的种子不总规范），**编码一律按键字典序输出**（规范形，info-hash 必须基于原始字节而非重编码）；
- 解析防护：递归深度上限 64、输入总大小上限 64MB（防 zip-bomb 结构攻击）。

对外（core 内部 API，非公共）：`Bencode.decode(ByteBuffer)` 返回 sealed 类型
`BencodeValue`（Int / ByteString / List / Dict）；`Bencode.encode(...)` 输出规范形。

### 5.2 种子元数据解析（BEP 3 v1）

`TorrentMetadata` 模型字段：

- 顶层：`announce`（主 Tracker）、`announce-list`（BEP 12 分层）、`comment`、`created by`、`creation date`；
- `info` 字典：`name`、`length`（单文件）、`piece length`、`pieces`（20×N 字节 SHA-1 数组）、`private`。

关键设计——**info-hash 必须对原始字节计算**：解析器记录 `info` 字典在文件中的字节区间，
`SHA-1(原始区间)` 即 info-hash；禁止"解码后重编码再哈希"（规范形与原始字节可能不一致）。
其余字段用结构化模型表达。

### 5.3 HTTP Tracker 客户端（BEP 3 / 12 / 23）

Announce 请求（GET，参数按原始字节逐字节百分号编码——非标准 URL 编码）：

| 参数 | 值 |
|---|---|
| `info_hash` / `peer_id` | 20 字节原始值逐字节编码 |
| `port` / `uploaded` / `downloaded` / `left` | 本机监听端口 / 累计上传 / 累计下载 / 剩余字节 |
| `compact` / `no_peer_id` | `1` / `1`（BEP 23） |
| `event` | `started` / 定期（空）/ `completed` / `stopped` |
| `numwant` | 50 |

响应解析：`interval`（下次轮询秒数）、`complete` / `incomplete`、紧凑 Peer 表（每 6 字节
= 4 字节 IPv4 + 2 字节大端端口）、`failure reason`。

BEP 12 分层策略：`announce-list` 按 tier 逐层尝试，tier 内随机起点轮转；当前 tier 全部失败
则切下一 tier；某 announce 连续失败按 `interval × 2` 指数退避（上限 30 分钟）。

### 5.4 Peer 线协议（BEP 3）

握手：`<19>"BitTorrent protocol"<8 字节保留位><20 info-hash><20 peer-id>`。
阶段 1 保留位全零（阶段 2 置 DHT bit），为扩展留门。Peer ID 采用 BEP 20 前缀 `-JT0001-` + 12 位随机。

消息帧：4 字节大端长度前缀 + 1 字节消息 ID + 载荷；长度 0 为 keep-alive（60s 无消息则断开）。

| ID | 消息 | 载荷 | 约束 |
|---|---|---|---|
| 0 | choke | — | |
| 1 | unchoke | — | |
| 2 | interested | — | |
| 3 | not interested | — | |
| 4 | have | piece 序号 u32 | |
| 5 | bitfield | 位图字节 | 仅允许作为握手后首条消息 |
| 6 | request | index、begin、length（=16KiB） | 仅在被 unchoke 且已声明 interested 后发送 |
| 7 | piece | index、begin、数据 | begin+len 不得越界 |
| 8 | cancel | 同 request | endgame 收到重复块后取消其余请求 |

异常帧（长度前缀越界、piece 越界、未知 ID）→ 断开该 Peer。

### 5.5 存储层（core.internal.storage）

- **预分配**：任务启动即按 `length` 创建目标文件（`.part` 后缀，完成后改名），Windows 下
  `setLength` 预留空间，既防中途磁盘不足又减少碎片；
- **Block 直写**：收到的 Block 直接 `pwrite` 到 `pieceOffset + begin` 的最终偏移——内存占用
  与 Piece 大小无关（对比"内存攒整 Piece"方案：4MB Piece × 50 Peer 的峰值内存不可控）；
- **Piece 读回校验**：一个 Piece 的全部 Block 齐后，顺序读回该区间计算 SHA-1 与 `pieces`
  比对：通过 → 位图置位、`flush`；失败 → 该区间清零重下，并给来源 Peer 记一次坏块
  （同一 Peer 累计 2 次坏块 → 任务内永久拉黑，防恶意注入）。

### 5.6 分片下载引擎

- 请求管线：每 Peer 同时在途 request ≤ 5（16KiB × 5 ≈ 80KB 窗口，虚拟线程阻塞读天然背压）；
- 流量控制即"固定小窗口 + 停等补充"，不实现 TCP 之外的复杂窗口算法；
- 上行：被 unchoke 的 Peer 的 request 及时响应 `piece`（做种/互惠上传），受上传限速器约束。

### 5.7 断点续传（`.jt-resume` 状态文件）

伴随 `.part` 文件的状态文件，小端序二进制布局：

| 偏移 | 长度 | 字段 |
|---|---|---|
| 0 | 8 | magic `JTRESUME` |
| 8 | 2 | 版本 u16（=1） |
| 10 | 20 | info-hash（重启时校验状态文件确属此种子） |
| 30 | 4 | pieceCount u32 |
| 34 | ⌈N/8⌉ | Bitfield |
| … | 8 | uploadedTotal u64 |
| … | 8 | downloadedTotal u64 |
| … | 8 | lastActiveEpochMs u64 |
| … | 4 | 全文 CRC32 |

写入时机：每完成一个 Piece + 任务暂停/关闭时。重启流程：`.part` + `.jt-resume` 都存在 →
先按 `verifyOnRestart` 对位图中已置位的 Piece 重校验（防文件被外部改动），损坏位清零，
进入 DOWNLOADING 续传；不存在则全量新下。**第三阶段**优化为抽样校验（格式已预留版本号）。

### 5.8 Piece 调度器

- 统计 availability：聚合所有已连接 Peer 的 Bitfield 得每 Piece 持有数；
- **首块随机**：任务初期随机选块，尽快凑出可交换的完整 Piece；
- **rarest-first**：此后在被任一连接 Peer 持有的 Piece 中选持有数最少者（并列随机），
  维持 Swarm 健康度；
- **endgame**：当所有未完成 Piece 都已有在途请求时进入终局模式——向所有持有者重复请求
  缺失 Block，收到一个即对其余发 `cancel`，消除长尾等待；
- 同一 Peer 内同一时间只调度它持有的 Piece。

### 5.9 Choking 算法（标准 tit-for-tat）

- 每 10 秒重算：unchoke "对我上传速率最高的 4 个 Peer"（互惠）；
- 每 30 秒轮换 1 个**乐观槽**：随机 unchoke 一个对我 interested 且当前被 choke 的 Peer——
  给新加入者公平机会，否则冷启动死锁；
- 做种期（SEEDING）：改为 unchoke "从我下载最多的 4 个" + 1 随机新 Peer；
- interested 状态独立维护：对方有我缺的 Piece 即声明 interested，避免无意义请求。

### 5.10 限速器（令牌桶）

- 容量 = burst（默认 1 秒配额，下限 16KiB），连续平滑补充（非整秒突刺）；
- 两级串联：全局桶（client 级，跨任务共享）∧ 任务桶，均需放行；
- 生效点：下载侧在读取 `piece` 载荷前 acquire；上传侧在发送 `piece` 前 acquire；
  拿不到令牌→虚拟线程 sleep 等待（虚拟线程使阻塞等待零成本）；
- 默认 0 = 不限速（直通桶）。

### 5.11 testkit 与 CLI（阶段收尾）

- `javathunder-testkit`：`EmbeddedTracker`（基于 `com.sun.net.httpserver`，实现 announce
  协议、内存 Peer 表）、`TorrentGenerator`（生成随机文件 + 对应 .torrent，内部复用 core
  的 bencode 编码器）、`FakeSeeder`（TCP 服务：应答握手与 request，按文件提供 piece）；
- `javathunder-cli`：`javathunder <torrent> [--dir D] [--port N] [--limit-x MB/s]`，
  ASCII 进度条 + 速度 + ETA + Peer 数 + 健康度，作为库用法的活示例。

### 5.12 阶段一验收

见 1.3；另加：限速实测（设 1MB/s，稳态偏差 ≤ ±20%）、500 Peer 模拟连接下 RSS ≤ 256MB、
CPU 单核可服务（testkit 压测脚本验证）。

---

## 6. 第二阶段（完整模式）详细设计

### 6.1 扩展握手（BEP 10，已实现）

握手保留位 `reserved[5] & 0x10`（从右数第 20 bit）置 1——注意 BEP 10 规范选定的是
保留区第 6 字节，不是末字节（末字节 0x01 属 DHT/BEP 5）；握手后互发 `extended
handshake`（消息 ID 20，子 ID 0）：`m` 字典声明各扩展的消息子 ID（如
`{"m":{"ut_metadata":1,"ut_pex":2}}`）+ `metadata_size`。这是一切扩展的载体。

实现：`Handshake` 编码置位/`supportsExtensions` 判位；`ExtendedMessage`（id 20，子 ID u8
+ bencoded 载荷）+ codec 编解码（空 sub-id 帧拒绝）；正常下载会话对扩展消息忽略容忍。

### 6.2 磁力链接 + ut_metadata（BEP 9，已实现）

- API：`client.download(MagnetUri.parse("magnet:?xt=urn:btih:…&dn=…&tr=…"))`；
- 流程：解析 info-hash（hex/base32）与可选 tracker 列表 → 正常连 Peer（DHT/Tracker 发现）→
  `ut_metadata` 分 16KiB 块请求 info 字典 → 重组后 SHA-1 自校验 = info-hash → 转正常任务流程。

实现要点：`MetadataFetcher` 从 tracker 取 Peer，扩展握手后按对端 `m` 子 ID + `metadata_size`
分块请求；data 应答为 bencoded 头 + 原始字节，头部边界由解码器消费量决定（严格 decode 会
因 trailing data 误拒）。SHA-1 不符丢弃换 Peer，60s 总超时。`MagnetDownloadTask` 两段式：
元数据期映射 QUEUED/空快照，就绪后经事件线程切换到普通会话（槽位只占元数据阶段，避免与
会话槽双持有死锁）。Peer 发现当前仅 tracker（DHT 阶段 2 接入）。

### 6.3 DHT（BEP 5，Kademlia）

- 独立模块 `javathunder-dht`（core 的可选依赖）：160-bit ID 空间、K-bucket 路由表（K=8）、
  KRPC over UDP（`ping` / `find_node` / `get_peers` / `announce_peer`，bencoded 字典报文）；
- bootstrap：`router.bittorrent.com:6881` 等公共节点 + 种子内 `nodes` 字段；
- 集成点：DHT 是 Peer 来源之一，产出的地址进 PeerManager 候选池，与 Tracker 来源合并；
- **`private` 种子（BEP 27）强制禁用 DHT 与 PEX**。

### 6.4 PEX（BEP 11）

通过 BEP 10 通道周期性（≈60s）交换 `added` / `dropped` Peer 地址；`private` 种子禁用。

### 6.5 UDP Tracker（BEP 15）

16 字节精简报文协议：`connect`（60s 缓存 connection_id）→ `announce`；事务 ID 校验、
指数退避重传；与 HTTP Tracker 并列为 Peer 来源，优先级相同。

### 6.6 多文件种子（B3，已实现）

`info.files[]`（`path[]` + `length`）按累积偏移映射到同一 Piece 流：`TorrentMetadata.files`
记录每个文件的拼接流偏移，`length` = 总和，Piece 可跨文件边界。引擎按种子形态经
`TorrentStorage` 接口二选一：单文件走 `StorageManager`（原路径不动），多文件走
`MultiFileStorage`——读写以拼接流偏移 scatter 拆分（单个缓冲可跨文件边界），
零拷贝 `writePieceBuffers` 的 gather 顺序与拼接流一致。

**暂存编号文件策略**：下载期数据写 `<name>.part/00000..N`（按文件序号的稀疏预分配
中间文件），半成品不以真实文件名出现在目标目录；`finish()` 时按种子 `path[]` 建目录树、
`move` 落位改名并删除暂存目录（空文件直接建占位）。resume 文件仍以 `<name>.jt-resume`
挂在暂存目录旁。

**路径穿越防护清单**（解析期拒绝，防恶意种子逃出目标目录）：空组件、`..`、`/`、`\`、
`:`（含盘符）、控制字符（<0x20）、Windows 保留设备名 `CON|PRN|AUX|NUL|COM1-9|LPT1-9`
（含带扩展名的 stem 提取，如 `com1.txt`）；另校验 `files` 非空列表、逐件 `length >= 0`、
总长为正、Piece 数对总长一致。验收：跨文件边界件端到端字节比对 + ttorrent 多文件做种互操作。

### 6.7 阶段二验收

磁力链接（无 .torrent 文件）完整下载并校验；关闭所有 Tracker 仅靠 DHT 完成下载；
PEX 在无 Tracker 场景下维持 Peer 补充；`private` 种子验证 DHT/PEX 确未启用。

---

## 7. 第三阶段（按需/远期）

| 项 | 内容 | 触发条件 |
|---|---|---|
| BEP 6 快速扩展 | `Have All/None`、`Reject Request`、`Suggest Piece` | 互操作收益明确时 |
| BEP 19 Web 种子 | HTTP URL 列表作为兜底下载源 | 用户明确需求 |
| BEP 52 v2/混合种子 | Merkle 树 + SHA-256、文件对齐 Piece | v2 生态占比不可忽视时 |
| 抽样续传校验 | `.jt-resume` v2：时间戳信任 + 抽样 SHA-1 | 大文件重启耗时成为痛点时 |

---

## 8. 非功能需求

- **性能**：500 并发连接 RSS ≤ 256MB、CPU ≤ 1 核；下载吞吐不设硬指标（受限于 Swarm），
  但不得低于 aria2c 同环境吞吐的 70%；
- **可靠性**：任何单 Peer 的异常数据/异常帧不得污染任务（校验 + 拉黑）；任务级故障
  （Tracker 全挂且无 Peer）进 FAILED 并携带原因，不静默卡死；
- **互操作**：与 aria2、Transmission、qBittorrent 的握手与传输互通为验收项；
- **安全**：bencode 深度/大小防护；多文件路径穿越防护（6.6）；info-hash 与 resume 文件
  绑定防串档；不做协议加密（非目标）；
- **可观测性**：slf4j 全量日志（Peer 生命周期、announce、坏块、限速命中率）；
  事件 API 承接结构化监控。

---

## 9. 工程实践

- **git**：每完成一个功能提交一次，conventional commits（`feat:` / `fix:` / `docs:` / `build:`），
  主干开发；
- **测试策略**：单元（bencode/解析/位图/限速/调度纯逻辑）→ testkit 集成（回环实验室）→
  互操作（aria2c 脚本）→ 公网验收（Ubuntu ISO）；
- **CI**（GitHub Actions）：JDK 21/25/26 × ubuntu/windows，`mvn verify` + 首个 tag 后
  japicmp 基线；
- **开发序列**（即任务清单）：Bencode → 种子解析 → HTTP Tracker → Peer 线协议 →
  分片下载/校验/落盘 → 断点续传 → 调度/choking → 限速 → testkit/CLI → 回环验收 → 互操作 → 公网验收。

---

## 10. 风险与开放问题

| 风险 | 应对 |
|---|---|
| JDK 21 虚拟线程 `synchronized` pinning（JDK 24 才修复） | 锁内长等待一律 `ReentrantLock`（ADR-0001 已约定） |
| HTTP Tracker 在公网生态中占比下降 | 阶段 2 引入 UDP Tracker + DHT 覆盖 |
| 与真实客户端互操作的长尾细节（百分号编码、bencode 严格性） | 回环实验室 + aria2 互操作纳入每阶段验收 |
| 单人项目范围蔓延 | 每阶段验收清单冻结，未列项进 ROADMAP 不进代码 |
| v2 种子生态演进 | 第三阶段评估，握手保留位不堵死升级路径 |
