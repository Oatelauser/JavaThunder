# JavaThunder 集成操作手册

**面向读者**：在自己的 JVM 项目里对接本库的开发者（下载方/做种方/平台集成方）。
**对应版本**：0.2.0（坐标 `io.github.oatelauser`）。**契约基准**：`javathunder-api` 模块的公共接口；
实现类位于 `*.internal.*` 包，不对其做任何兼容承诺。

---

## 1. 这个库是什么 / 不是什么

**是**：嵌入式 BitTorrent 引擎——你的应用依赖它，获得"给一个种子/磁力链接，还你一个校验通过的本地文件"的能力，同时你的进程自动成为 P2P 网络的一个节点（下载中互惠上传、完成后可做种）。

**不是**：带界面的下载器（UI 由你用事件 API 自建，CLI 只是示例）、HTTP 下载器、离线下载服务。

**两种形态是同一套 API 的配置特例**（无模式枚举）：

| | 轻量使用 | 完整使用 |
|---|---|---|
| 依赖 | `javathunder-core`（传递 `javathunder-api`） | + `javathunder-dht`（可选） |
| Peer 发现 | 仅 tracker | tracker + DHT + PEX |
| 典型场景 | 嵌入式按需拉文件 | 内网镜像分发节点/做种服务 |
| 内存/连接 | 默认值即偏保守 | 调高 `maxPeersPerTask`/`maxConcurrentTasks` |

---

## 2. 五分钟入门

### 2.1 依赖

```xml
<dependency>
  <groupId>io.github.oatelauser</groupId>
  <artifactId>javathunder-core</artifactId>
  <version>0.2.0</version>
</dependency>
```

运行时仅依赖 `slf4j-api`——**请自带日志后端**（如 `slf4j-simple` 或 logback），否则日志静默。

### 2.2 最小可运行示例

```java
import io.github.oatelauser.thunder.api.*;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;

try (TorrentClient client = DefaultTorrentClient.builder().build()) {  // 默认端口 6881
    DownloadTask task = client.download(
        java.nio.file.Path.of("ubuntu.torrent"),
        DownloadOptions.defaults().targetDir(java.nio.file.Path.of("downloads")));

    task.addListener(new TaskListener() {           // 事件接口有 7 个方法，按需覆写
        @Override public void onProgress(ProgressSnapshot p) {
            System.out.printf("%.1f%%  eta=%s%n", p.fraction() * 100, p.etaMillis());
        }
    });

    DownloadResult result = task.future().join();     // 完成即：每个 Piece SHA-1 校验通过
    System.out.println("done: " + result.file());
}
```

> `TorrentClient` 接口是稳定契约；其实现 `DefaultTorrentClient` 目前位于 core 的 internal 包（0.x 阶段现状，构造入口是其公共静态 `builder()`）。

### 2.3 CLI 冒烟（不写代码先感受）

```bash
java -jar javathunder-cli.jar download ubuntu.torrent --dir downloads
# 进度行：百分比 下载/上传速率 peers 健康度 eta
```

---

## 3. 核心对象模型（一分钟版）

```
TorrentClient            一个进程一个；持有监听端口/全局限速/事件线程；AutoCloseable
  └─ download(...) ──▶ DownloadTask   一个种子一个；句柄，全部方法线程安全
        ├─ future()        CompletableFuture<DownloadResult>，异常/取消都走它
        ├─ snapshot()      任意时刻的 ProgressSnapshot
        ├─ pause()/resume()/cancel(boolean deleteData)
        └─ addListener(TaskListener)
DownloadOptions          单任务选项（目标目录/限速/做种/断点校验档），不可变 record
TaskListener             7 个 default 方法的事件接口，回调在你的（或库的）事件线程上
```

---

## 4. 轻量 vs 完整：两组典型配置

**轻量**（嵌入式按需拉取：少量连接、下完即停、少占带宽）：

```java
DefaultTorrentClient.builder()
    .listenPort(6881)
    .maxConcurrentTasks(1)
    .maxPeersPerTask(8)
    .downloadLimitBytesPerSecond(1024 * 1024)      // 全局 1MB/s
    .build();

client.download(torrentPath,
    DownloadOptions.defaults().targetDir(dir).rateLimits(512 * 1024, 64 * 1024));
```

**完整**（镜像分发节点：DHT 去 tracker 化、大件抽样校验、下完转做种）：

```java
DefaultTorrentClient.builder()
    .listenPort(6881)
    .maxConcurrentTasks(4)
    .maxPeersPerTask(64)
    .peerDiscovery(DhtPeerDiscovery.create())      // 公网自举；内网见 §5.7
    .build();

client.download(torrentPath,
    DownloadOptions.defaults()
        .targetDir(mirrorDir)
        .restartVerify(RestartVerifyMode.SAMPLED)  // TB 级断点恢复不重扫全盘
        .rateLimits(0, 0));                        // 不限
// seedAfterComplete 见 §5.6
```

---

## 5. 场景手册

### 5.1 下载 .torrent 文件（基础）

见 §2.2。要点：`targetDir` 是保存目录；下载期间文件以 `<name>.part` 存在（预分配全尺寸），校验完成原子改名为最终名——**半成品永远不会顶着正式文件名**。

### 5.2 磁力链接（无 .torrent 文件）

```java
MagnetUri magnet = MagnetUri.parse(
    "magnet:?xt=urn:btih:<40位hex或32位base32>&dn=名称&tr=http://tracker/announce");

DownloadTask task = client.download(magnet, DownloadOptions.defaults().targetDir(dir));
```

机制：引擎连上 Peer → BEP 10 扩展握手 → BEP 9 ut_metadata 分块拉取 info 字典 →
**SHA-1 必须等于磁力里的 info-hash（错元数据直接丢弃换源）** → 转入正常下载。
前提：至少一个可用 tracker（`tr=` 参数）或注入了 DHT（§5.7）。元数据阶段对外表现为
`QUEUED` + 空快照，就绪后事件/快照无缝衔接。

### 5.3 多文件种子 / 模型目录

无需任何特殊 API——多文件种子自动识别：

```java
DownloadResult r = client.download(Path.of("model-x.torrent"), options).future().join();
// r.file() = 目标目录下的种子根目录（含完整子树），例如：
//   downloads/model-x/weights.bin
//   downloads/model-x/nested/config.json
```

Piece 覆盖文件拼接字节流（可跨文件边界），实现层已处理；**路径穿越防护**内置
（拒绝 `..`/绝对路径/盘符/保留设备名等，恶意种子无法逃出 `targetDir`）。

### 5.4 断点续传与重启校验三档

进度持久化在 `<name>.part` 旁的 `<name>.jt-resume`（CRC32 + info-hash 绑定，崩溃最多丢
~2 秒进度）。进程重启后对同一 targetDir 重新 `download(...)` 即自动续传；对"已完成件"的
信任程度由档位决定：

```java
options.restartVerify(RestartVerifyMode.FULL)     // 默认：逐件 SHA-1 重校验
options.restartVerify(RestartVerifyMode.SAMPLED)  // ~10% 随机抽样 + 首末件必查；大镜像推荐
options.restartVerify(RestartVerifyMode.NONE)     // 完全信任位图（最快，代价见 javadoc）
```

漏检的坏件不会污染分发：做种时对端校验失败会触发坏件重下自愈路径。

### 5.5 限速（两级 × 双向）

```java
// 全局（client 级，跨任务共享令牌桶）
builder.downloadLimitBytesPerSecond(10 * 1024 * 1024)
       .uploadLimitBytesPerSecond(2 * 1024 * 1024);

// 任务级（与全局串联：两者都需放行；0 = 该级不限）
options.rateLimits(downloadBps, uploadBps);
```

实测精度（回环）：目标 512KB/s 时实测 537–538KB/s（含令牌桶初始突发，属预期）。

### 5.6 做种与上传对接（第三方如何从你这里下载）

**上传是自动的，不需要写任何"上传代码"**：

1. **下载中互惠上传**：别的客户端请求你已完成的 Piece，引擎按 tit-for-tat 回应
   （谁给你传得多你优先回馈谁 + 每 30s 一个乐观槽给新节点）。
2. **完成后持续做种**：
   ```java
   new DownloadOptions(dir, true, true, /*seedAfterComplete=*/true, 0, 0, RestartVerifyMode.FULL)
   ```
   完成后状态转 `SEEDING`，继续应答他人请求，直到你 `cancel`/`close`。
3. **纯做种已有数据**：当前版本通过"完整下载一次后转做种"路径实现；或高级用法——
   预填 `.part` + `.jt-resume`（格式见 DESIGN §5.7 与 testkit 的 RestartVerifyModeTest 示例）。

Windows 注意：做种期间文件保持 `.part` 名（句柄占用），这是 Known Limit。

### 5.7 内网镜像分发完整拓扑（大模型场景参考实现）

```text
源机器（seed-01）                          目标机器 ×N
┌──────────────────────┐                  ┌──────────────────────┐
│ model-x/  (目录树)    │   HTTP/UDP       │ JavaThunder           │
│  ├ weights.bin       │   tracker         │   .torrent 或磁力     │
│ JavaThunder          │◀────announce────▶│   download()+做种      │
│  seedAfterComplete   │                  │ （下完互为种子源）      │
└──────────────────────┘                  └──────────────────────┘
```

搭建步骤（全在你内网，零公网依赖）：

```java
// 1) 造种子：把目录树打成 .torrent（testkit 的生成器可直接用于生产脚本）
TorrentGenerator.generateMultiFile(dir, "model-x", List.of(
        List.of(List.of("weights.bin"), 50_000_000_000L),
        List.of(List.of("tokenizer.json"), 2_000_000L)),
    4 * 1024 * 1024, "http://tracker.lan:6969/announce", new Random());

// 2) tracker：任选——内网起一个 HTTP tracker（opentracker 十几行配置），
//    或 BEP 15 UDP tracker；种子 URL 写 udp://tracker.lan:6969/announce 亦可（自动分派）
//    也可以完全去 tracker：两端注入自建 DHT bootstrap 的 PeerDiscoverySource：
DhtPeerDiscovery.create(List.of("seed-01.lan:6881"));   // 源机器即自举节点

// 3) 源机器：完整下载一次（或预填）→ seedAfterComplete=true 持续做种
// 4) 目标机器：普通 download；rarest-first 保证 N 台目标互相取缺，不都挤源机器
//    断网/重启 → 同 targetDir 重新 download 自动续传（建议 SAMPLED 档）
```

### 5.8 事件与监控集成（UI/指标怎么挂）

```java
task.addListener(new TaskListener() {
    @Override public void onStateChanged(TaskState from, TaskState to) { /* 状态机迁移 */ }
    @Override public void onProgress(ProgressSnapshot p) {
        // ~500ms 一帧：fraction/rates(EMA 平滑)/connectedPeers/availability/etaMillis
        metrics.gauge("bt.fraction", p.fraction());
    }
    @Override public void onPieceComplete(int pieceIndex) { /* 分片级进度位图 */ }
    @Override public void onTrackerAnnounce(String url, String failure, int seeders, int leechers) {}
    @Override public void onPeerConnected(String addr) {}
    @Override public void onPeerDisconnected(String addr, String reason) {}
    @Override public void onError(Throwable error) {}
});
```

线程契约（重要）：回调**永不**运行在协议线程上——默认投递到库内单线程事件线程，
可用 `builder.listenerExecutor(yourExecutor)` 改为你的线程（如 UI 线程/队列）；
回调抛异常被吞掉不影响协议；事件尽力保序不承诺严格有序。

### 5.9 生命周期与线程

- `TorrentClient implements AutoCloseable`：close = 停全部任务、断全部连接、幂等可重入
- 库内线程全部 daemon 且命名 `javathunder-*`——不会挂住你的 JVM 退出；jstack 可按名过滤
- 所有用户可见阻塞方法（close 等）可中断
- `pause()` 保留连接上下文停止请求；`resume()` 继续；`cancel(true)` 连本地数据与状态文件一起删

### 5.10 在你自己项目里做集成测试（testkit）

```xml
<dependency>
  <groupId>io.github.oatelauser</groupId>
  <artifactId>javathunder-testkit</artifactId>
  <version>0.2.0</version>
  <scope>test</scope>
</dependency>
```

```java
try (EmbeddedTracker tracker = EmbeddedTracker.start()) {                  // 内嵌 HTTP tracker
    var t = TorrentGenerator.generate(dir, "payload.bin", 1_500_000,       // 造种子+内容
        tracker.announceUrl(), new Random(42));
    var meta = TorrentParser.parse(Files.readAllBytes(t.torrentFile()));
    try (FakeSeeder seeder = FakeSeeder.start(t.contentFile(), meta)) {    // 已知良好种子方
        seeder.announceTo(tracker);
        try (var client = DefaultTorrentClient.builder().build()) {        // 被测代码
            var result = client.download(t.torrentFile(),
                DownloadOptions.defaults().targetDir(out)).future().get();
            // 断言字节一致
        }
    }
}
```

工具一览：`EmbeddedTracker`（HTTP tracker）/ `TorrentGenerator`（单/多文件种子）/
`FakeSeeder`·`NioSeeder`（阻塞/NIO 对端）/ `MetadataSeeder`（支持 BEP 9 的对端，测磁力）/
`Transports.fromSystemProperty()`（差分开关：`-Djavathunder.transport=nio` 对同一测试跑双传输）。

---

## 6. 重要 API 速查

### javathunder-api（稳定契约，japicmp 守护）

| 类型 | 关键成员 | 说明 |
|---|---|---|
| `TorrentClient` | `download(Path, DownloadOptions)` / `download(MagnetUri, ...)` / `close()` | 门面；两种入口 |
| `DownloadTask` | `future() state() snapshot() pause() resume() cancel(boolean) addListener(...)` | 任务句柄 |
| `DownloadOptions` | `defaults() targetDir() rateLimits(dl,ul) restartVerify(mode)` | 单任务配置 record |
| `MagnetUri` | `parse(String)` → infoHash/displayName/trackers | 磁力解析（hex/base32） |
| `TaskListener` | 7 个 default 方法（§5.8） | 事件回调 |
| `ProgressSnapshot` | fraction / rates / connectedPeers / availability / **etaMillis(@Nullable)** | ~500ms 快照 |
| `TaskState` | QUEUED→VERIFYING→DOWNLOADING→SEEDING/COMPLETED；PAUSED/FAILED/CANCELLED | 状态机 |
| `RestartVerifyMode` | FULL / SAMPLED / NONE | 断点校验档 |
| `PeerDiscoverySource` | `getPeers(infoHash)` / `warmUp()` / `close()` | 去 tracker 发现的 SPI（DHT 等的注入点） |

### DefaultTorrentClient.Builder（core，门面构造）

`listenPort(6881)` · `maxConcurrentTasks(3)` · `maxPeersPerTask(50)` ·
`download/uploadLimitBytesPerSecond(0=不限)` · `listenerExecutor(Executor)` ·
`peerDiscovery(PeerDiscoverySource)` · `transportFactory(Function<byte[],PeerTransport>)`（高级/调试）

### javathunder-dht（可选模块）

`DhtPeerDiscovery.create()`（公网自举）/ `create(List<String> bootstrap)`（内网自建）→ 传入 `peerDiscovery(...)`

---

## 7. 架构总览

### 7.1 模块分层

```
javathunder-api    纯接口+值类型（唯一依赖 JSpecify 注解）——你的编译期契约
javathunder-core   引擎实现（依赖 api）
  ├ bencode / metainfo      BEP 3 编解码、种子模型（info-hash 取原始字节区间）
  ├ tracker                 HTTP(BEP 3/23) + UDP(BEP 15) 客户端，按 URL scheme 自动分派
  ├ wire                    线协议消息（含 BEP 6/10 容忍解码）
  ├ peer.transport          传输抽象 PeerTransport/PeerChannel
  │    ├ BlockingTransport   每连接一虚拟线程的参照实现（默认）
  │    └ NioTransport        单 selector 事件循环 + 合帧批解码 + gather 批写
  ├ storage                 TorrentStorage 契约：单文件(StorageManager) / 多文件(MultiFileStorage)
  └ engine                  DownloadSession（调度/ choking/ 限速/ 断点/ PEX/ 事件）
javathunder-dht    BEP 5 查询模式客户端（KRPC/路由表/get_peers），经 SPI 注入 core
javathunder-testkit 集成测试工具（tracker/seeder/生成器/差分开关）
javathunder-cli    可执行示例
```

### 7.2 线程模型（ADR-0003：混合拓扑）

- **传输 I/O**：NIO 时单 selector 平台线程管全部连接（零阻塞：磁盘/哈希全在锁外）
- **磁盘与校验**：虚拟线程 worker 池（每任务一个池）
- **事件回调**：单线程事件 Executor（可注入）
- **定时**（tracker/choking/进度/PEX）：各一虚拟线程
- 锁纪律：selector 热路径无锁（并发容器）；调度/ choking 分片锁；锁内等待禁止（ReentrantLock 原则）

### 7.3 一条数据的路径（下载面）

```
selector 线程：读批→合帧解码→块网格零拷贝挂引用（无 memcpy）
    └─ 齐件 → worker：SHA-1(块序列直喂) → gather 直写 .part → 位图/resume 脏标/Have 广播
上传面（对称）：对端 Request → serve FIFO（保序）→ 读盘 → 限速 → piece
```

关键设计决策与权衡都有 ADR 存档：[0001 虚拟线程/NIO 取舍](adr/0001-virtual-threads-over-nio.md)、
[0002 api/core 分模块](adr/0002-api-module-split.md)、[0003 事件循环+混合线程](adr/0003-nio-event-loop-hybrid-threads.md)；
性能数据与瓶颈归因见 [PERFORMANCE.md](PERFORMANCE.md)。

---

## 8. 协议标准与兼容性（BEP 一览）

| BEP | 名称 | 实现程度 | 对集成方的含义 |
|---|---|---|---|
| 3 | BitTorrent v1 + 线协议 | 完整 | 单/多文件种子、握手/位图/choke/request/piece/cancel |
| 5 | DHT (Kademlia) | 查询模式（可选模块） | `get_peers`/`announce` 可用；不响应他人 query（不构成全节点） |
| 6 | 快速扩展 | 容忍解码 | 对端发 HaveAll/HaveNone/Reject 不掉线；本端不发 |
| 9 | ut_metadata 元数据交换 | 完整 | 磁力链接的元数据来源 |
| 10 | 扩展握手 | 完整 | 上述扩展的载体；仅对声明支持的对端协商 |
| 11 | PEX 节点交换 | 完整（IPv4） | 每 60s 与对端互换连接表；private 种子自动禁用 |
| 12 | 多 tracker 分层 | 完整 | announce-list 逐层轮换 |
| 15 | UDP Tracker | 完整 | `udp://` URL 自动分派；连接缓存 60s；退避重试 |
| 20 | Peer ID 规范 | 完整 | 本客户端前缀 `-JT0001-` |
| 23 | 紧凑 peer 表 | 完整 | 与字典模型自动兼容 |
| 27 | 私有种子 | 完整 | `private=1` → 强制禁用 DHT/PEX |
| 52 | v2/混合种子 | 未实现（观望） | 仅 v1 种子 |

**互操作验证**：与 ttorrent 双向（下载+做种，双传输）+ 公网 Ubuntu ISO 实测（[INTEROP.md](INTEROP.md)）。

---

## 9. 部署与运维要点

- **JDK**：21+（编译目标 21，CI 验证 21/25；26 部署目标在矩阵内）
- **磁盘布局**：`<dir>/<name>.part`（进行中）+ `<dir>/<name>.jt-resume`（进度态）；完成时改名/落位并删除状态文件
- **端口**：默认 6881/TCP 入站（可入站连接的关键，防火墙放行能显著改善互惠）；DHT/UDP tracker 是出站 UDP，无需放行
- **日志**：slf4j，DEBUG 级可见 peer 生命周期/tracker/坏件/pex 全量轨迹
- **Known Limits**：设计量级 ≤1000 连接/任务 ≤200 Peer；PEX 仅 IPv4；无 MSE 加密；Windows 做种期 `.part` 名

## 10. 版本与升级

- 0.x：API 可能演进，但 **japicmp 在 CI 上守护二进制兼容**（0.2.0 对 0.1.0 判定 MINOR）
- 升级建议锁版本号；破坏性变更必然伴随 major 版本与 CHANGELOG 说明
- 内部包（`*.internal.*`）无兼容承诺——只 import `io.github.oatelauser.thunder.api.*` 与 `DefaultTorrentClient`
