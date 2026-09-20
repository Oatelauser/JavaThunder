# Changelog

按版本记录**用户可见**的变更；纯内部重构详录于 git 历史。坐标：`io.github.oatelauser:javathunder-*`。

## 1.1.0（2026-09-18）

### 新增

- **DHT 响应式节点（BEP 5 全功能节点的应答面）**：`javathunder-dht` 此前是
  query-only（只查不应）——现响应他人的 ping / find_node / get_peers /
  announce_peer。网络公民义务（DHT 健壮性来自节点互相应答）之外的实际收益：
  向我们 announce 的 peer 会被索引（info-hash → 对端，TTL 30 分钟），他人的
  get_peers 命中时作为 values 返回；查询方同时被登记进路由表，表随查询活跃度
  保持新鲜。announce_peer 的 token 校验为无状态方案（SHA-256(进程密钥 ‖ 来源
  IP ‖ 5 分钟时段)，接受当前与上一时段），implied_port 按 BEP 5 语义取 UDP
  源端口。无公共 API 变化（DhtPeerDiscovery 面不动），无新增线程（挂在既有
  接收回调上）

### 修复与加固

- **阻塞传输死锁（ABBA 锁序反转）**：`BlockingChannel.closeWith` 持 channel 监视器
  期间回调 closeListener（进入 `synchronized(session)`），与引擎"持 session 锁调
  channel.close()"构成环形等待——多 Peer 下载收尾时永久挂死（虚拟线程参与，
  jstack 不可见，需 jcmd 转储定位）。1.0.0 前窗口极小（close 不碰已建连接），
  "close 关闭全部已建通道"修复将其放大成确定性死锁。修复：closeWith 幂等改
  CAS，注册表摘除/连接关闭/监听器回调全部移出监视器。发布门禁（阻塞臂
  MultiPeer）抓获，复现点 0.8s 通过（原挂死 16 分钟）
- **内嵌 UDP tracker 的 connect 校验（BEP 15 合规，公网暴露前置条件）**：
  connection_id 现与来源地址绑定（60s TTL、成功 announce 滑动续期、惰性清理），
  id 未知/过期/来源不符一律回 error(action=3) 且不注册 peer——伪造源地址不再能
  污染 peer 表
- **UDP tracker 客户端并发化**：共享 socket 由串行化事务改为收发分离 + 事务 ID
  分发表（tid→future），多任务并发 announce 不再互相排队（此前对端静默时最坏
  排队约 16s）；配合服务端 connect 校验，announce 收到 action=3 自动丢弃缓存
  重连一次（两侧 60s TTL 的时钟边界自愈）；close 立即异常完成全部在途请求
- **Peer 侧件认领闭环（对称 WebSeed）**：多 worker 并发选件与 WebSeed 认领的
  交错不再双占同件（重复传输窗口彻底关闭；补块合件语义不受影响）
- 修正 UdpTrackerServer 类注释的 event 偏移笔误（72→80）

## 1.0.0（2026-09-18）

### API 冻结（ADR-0005）

- **api 面冻结**：1.0.0 发布时的 javathunder-api 全部公共类型即对外承诺面，
  1.x 期间只做纯新增与实现修复，破坏性变更升 major（japicmp 对 tag 基线守护）
- **`DownloadOptions` 补齐 wither 自洽**：新增 `resumeEnabled(boolean)` 与
  `seedAfterComplete(boolean)`——自此 `defaults()` + wither 链可表达规范构造的
  全部取值（此前做种开关只能经原始构造设置，属冻结前的真实缺口）
- **4 个历史兼容构造升级 `@Deprecated(forRemoval = true)`**（6/7/8/9 参重载，
  编译期强警告 + 迁移注释），**2.0 移除**
- **文档事实纠正与补全**：`DownloadResult` 类注释不再声称"SHA-1 校验"（v2-only
  为 SHA-256 Merkle；选择性下载后为必需件集）；`DownloadTask` 全成员、`TaskState`
  全常量、`TorrentClient.download(Path)`、`MagnetUri.parse`、`SeedOptions`
  工厂/wither 补齐 Javadoc（语义/异常/边界）。无行为变更

### 修复（全项目规范走查产出，~145 文件审阅）

- **UDP tracker announce 请求编码修正为 BEP 15 标准**（客户端 + 内嵌 UDP 服务端
  两侧同步）：此前 `left`/`uploaded` 字段写反、`port` 误写 4 字节 int 且 98 字节
  缓冲尾部 6 字节未写满——真实 tracker 会解读为 `num_want=0`（不给 peer）与我方
  `port=0`（入站失效）；内嵌服务端此前镜像同一私有布局，第三方客户端无法直连。
  现两侧均为标准布局（含逐字段偏移断言的测试）
- **NIO 帧上限前置校验**：恶意对端仅凭 4 字节长度前缀声明超大帧（含 ≥0x80000000
  的负数化长度）即可在解码期检查生效前触发巨额堆分配；现读入即按无符号比对断连
  （与阻塞路径一致，新增专项测试）
- **内嵌 UDP tracker 服务线程不再被单次 IO 异常杀死**：Windows 上向已消失对端回包
  后的 ICMP 端口不可达会令下一次 receive 抛异常，旧实现静默退出整个 announce 线程；
  现仅正常关停退出（对齐 dht KrpcRpc 的同款处理）
- **v2-only 磁力层带装配竞态**：哈希请求批量发给全部对端，两会话并发交付同一块时
  计数越过零、整带终检永不触发（任务 60s 超时收场）；现以 `ConcurrentMap.remove(k,v)`
  原子占位，仅胜者装配计数
- **Windows 句柄两项**：`StorageManager` 构造器部分失败不再泄漏已开文件通道（锁
  文件）；`MultiFileStorage.finish()` 现关闭填充文件通道（`.part` 暂存目录此前删不掉）
- **零行为重构约 37 处**：方法下沉/卫语句/魔法值提名/死代码清除（含 KRPC 死方法、
  恒真断言、未用字段）/失实注释纠正——依据 AGENTS.md（Clean Code + 阿里 P3C 黄山版
  人工口径）逐文件执行，双臂全量测试零回归

### 修复（backlog 清零批）

- **磁力任务取消终态**：元数据阶段 `cancel()` 后 future 此前以
  `IllegalStateException` 异常完成，调用方无法区分"用户取消"与"失败"；现为
  正规取消态（`CancellationException`）——赶在 1.0 冻结前落定（此后即为 API 承诺）
- **磁力路径 `infoHashV2` 修正**：此前恒为 `sha256("")`（潜伏数据错误）；现透传
  真实 info 字节计算，主 infoHash 口径不变（v2-only 截断 20 字节）
- **UDP tracker 客户端并发互踩**：共享单 socket 上并发 announce 互相丢弃对方
  应答（靠超时重传自愈、peer 发现变慢）；现按 client 串行化事务，并防御 tid
  匹配的截短应答（此前越界读异常穿透）
- **阻塞传输 `close()` 语义对齐**：此前不关闭已建连接（读线程滞留至对端断开，
  最长 120s）；现与 NIO 一致关闭全部通道
- **杂项健壮性**：NIO 关停期 `ClosedSelectorException` 不再杀事件循环线程、
  大帧后排空缓冲收缩回初始容量、KRPC 事务 ID 碰撞不再顶掉在途请求、
  MetadataFetcher 坏元数据即时释放会话槽、WebSeed 件认领原子化、tracker 上报
  left 按必需件口径（选择性下载下不再偏小）、导入做种不再物化 `.pad` 空文件
  （pad 段零合成）
- **测试卫生**：MultiPeer 性能探针缺省臂统一为 NIO（与生产一致）、误导性测试名
  改名、CLI 悬空 `--dir` 报 usage、EmbeddedTracker 资源化管理等六项；断言语义
  零触及

## 0.8.0（2026-09-18）

### 新增

- **文件级下载优先级（`FilePriority`）**：`DownloadOptions.filePriorities(...)`——
  HIGH 先拉 / NORMAL 常规 / SKIP 不下（任意 int 可用，越大越先），与 `FileFilter`
  组合（过滤器先取舍，优先级定次序；跨界件取保留侧最高优先级）。Peer 与 WebSeed
  两条通道同字典序（优先级降序，稀缺/索引次之）；只影响次序，不影响完成判定与
  进度语义。顺序模式下高优先级文件整体先于常规文件按序下载
- **多文件 WebSeed（BEP 19 目录形态）**：url-list 指向 HTTP 目录 base（末尾带
  `/`），引擎按 种子内相对路径 + 文件内 Range 逐段取回拼件——v1 跨文件边界的件
  多段拼接，v2 对齐布局单段，BEP 47 填充段本地零合成（不发 HTTP）。单文件种子的
  既有语义（URL 即文件）不变；源级轮询/退避/熔断与单文件共享

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
- **修复：CPU 负载下向 ttorrent 类客户端供出损坏件**：供种执行器原为每请求一条
  虚拟线程（FIFO 提交但并发执行），磁盘读/限速等待的完成序在负载下重排——晚到的
  块 0 会触发 ttorrent 1.5 Piece.record 的整片缓冲重置，拼出带洞件（SHA 不符，
  并连锁炸掉其接收线程）。改为每会话单线程 FIFO 虚拟线程执行器（提交序 = 写出序），
  跨 Peer 仍并行；回环吞吐 139MB/s（基线 140，无回退）。CI 互操作腿自 v0.6.0 起
  红灯的根因；CPU 饥和下 4/4 复现、修复后 6/6 通过
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
