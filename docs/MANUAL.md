# JavaThunder 使用手册

版本：v0.2.0 · 坐标：`io.github.oatelauser` · 要求：JDK 21+

**怎么读这本手册**：第 1 章澄清角色概念（服务端/客户端到底有没有）；第 2 章是完整可运行的快速入门（公网/离线两版）；第 3 章回答"什么时候引哪个包"；第 4 章按场景逐一给完整示例（每个功能一节、每节开头标注本例需要的依赖）；第 5–9 章是速查与排错。

---

## 第 1 章 先澄清概念：有没有"服务端和客户端"？

**没有传统意义上的服务端和客户端。** 这是 P2P 协议——每个 JavaThunder 实例都是**对等节点（Peer）**，同时具备下载和上传能力。你只需区分四种角色：

| 角色 | 是什么 | 谁来充当 | 你必须部署吗 |
|---|---|---|---|
| **下载者（Leecher）** | 还没拿全数据的节点；**边下边传** | 你的应用 | — |
| **做种者（Seed）** | 拿全数据的节点，只上传 | 任何完整持有数据的节点（含下载完成的下载者） | 分发场景需要至少 1 个 |
| **Tracker** | "电话簿"：告诉节点彼此的地址。**不碰文件数据** | 任何一个 HTTP/UDP tracker 程序 | 不必须（种子里有地址就用；可被 DHT 替代） |
| **DHT 网络** | 去 tracker 化的分布式电话簿 | 所有参与的节点 | 不必须（可选模块） |

**上传和下载的关系**：同一条 TCP 连接上的两个方向，引擎自动双向处理。你**不需要写任何"上传代码"**——只要你的节点持有某 Piece，其他客户端请求时引擎自动应答（详见 4.4 做种一节）。所谓"做种"只是"完整持有 + 持续在线"的状态。

---

## 第 2 章 快速入门（完整可运行）

### 2.0 前置：依赖（所有场景的共同起点）

```xml
<properties><maven.compiler.release>21</maven.compiler.release></properties>

<dependencies>
    <!-- 引擎：任何时候都只需要它（会传递引入 javathunder-api） -->
    <dependency>
        <groupId>io.github.oatelauser</groupId>
        <artifactId>javathunder-core</artifactId>
        <version>0.2.0</version>
    </dependency>
    <!-- 日志后端：本库只依赖 slf4j-api，不带后端会静默无日志。示例用 simple，生产换 logback -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-simple</artifactId>
        <version>2.0.17</version>
    </dependency>
</dependencies>
```

### 2.1 版本一：下载公网真实种子（Ubuntu 官方 ISO）

**适用**：有互联网环境，想先看真实效果。

```bash
# 1) 拿一个真实种子文件（Ubuntu 官方，合法）
curl -fSL -o ubuntu.torrent https://releases.ubuntu.com/24.04/ubuntu-24.04.4-desktop-amd64.iso.torrent
```

```java
// 2) QuickStart.java —— 完整类，直接运行
import io.github.oatelauser.thunder.api.*;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;
import java.nio.file.Path;

public class QuickStart {
    public static void main(String[] args) throws Exception {
        try (TorrentClient client = DefaultTorrentClient.builder().build()) {
            DownloadTask task = client.download(
                Path.of("ubuntu.torrent"),
                DownloadOptions.defaults().targetDir(Path.of("downloads")));

            task.addListener(new TaskListener() {
                @Override public void onProgress(ProgressSnapshot p) {
                    System.out.printf("\r%.1f%%  ↓%dKB/s  peers=%d  eta=%ss ",
                        p.fraction() * 100, p.downloadRateBps() / 1024,
                        p.connectedPeers(), p.etaMillis() == null ? "-" : p.etaMillis() / 1000);
                }
            });

            DownloadResult result = task.future().join();   // 返回即全部 Piece 校验通过
            System.out.printf("%n完成: %s (%d 字节)%n", result.file(), result.bytes());
        }
    }
}
```

**预期行为**：进度行每 ~0.5 秒刷新；结束后 `downloads/` 出现完整 ISO（下载期间是同名 `.part`）。命令行不写代码等价物：`java -jar javathunder-cli.jar download ubuntu.torrent --dir downloads`。

### 2.2 版本二：零网络本地 Swarm（离线也能跑通）

**适用**：内网开发机/CI 里验证集成，不依赖任何外部网络和 tracker。**需要额外引入 `javathunder-testkit`**（它不只是测试工具，也用于造种子、搭本地小 swarm）：

```xml
<dependency>
    <groupId>io.github.oatelauser</groupId>
    <artifactId>javathunder-testkit</artifactId>
    <version>0.2.0</version>
</dependency>
```

```java
import io.github.oatelauser.thunder.api.*;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;
import io.github.oatelauser.thunder.testkit.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class OfflineQuickStart {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of("sandbox");
        Files.createDirectories(dir);

        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {               // ① 电话簿
            var gen = TorrentGenerator.generate(dir, "hello.bin", 1_000_000,     // ② 造种子+内容
                tracker.announceUrl(), new Random(42));
            var meta = io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser
                .parse(Files.readAllBytes(gen.torrentFile()));

            try (FakeSeeder seeder = FakeSeeder.start(gen.contentFile(), meta)) { // ③ 种子源
                seeder.announceTo(tracker);
                try (TorrentClient client = DefaultTorrentClient.builder().build()) {  // ④ 下载方
                    DownloadResult r = client.download(gen.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")))
                        .future().join();
                    System.out.println("完成: " + r.file());
                }
            }
        }
    }
}
```

---

## 第 3 章 依赖引入指南：什么时候引哪个包

| 你的需求 | 需要引入 | scope |
|---|---|---|
| 下载 .torrent / 磁力（种子里有 tracker）/ 做种 / 限速 / 事件 | `javathunder-core`（+slf4j 后端） | compile |
| 磁力链接**且没有 tracker**，或完全去 tracker 分发 | **再加** `javathunder-dht` | compile（不需要时别引，轻量） |
| 造种子 / 本地测试 swarm / 集成测试对端 | **再加** `javathunder-testkit` | test（运维脚本可用 compile） |
| 想要命令行工具演示 | `javathunder-cli` | 直接运行 jar |

判断规则一句话：**默认只引 core；只有"去 tracker"才引 dht；只有"造种子/自测"才引 testkit。**

---

## 第 4 章 场景手册（每个功能一节、每节标注依赖）

> 以下示例延续 2.0 的依赖；每节开头"**依赖**"行说明额外要引什么。

### 4.1 下载网上的种子文件（最常见）

**依赖**：仅 core。

从任何网站下载 `.torrent` 文件后（就像 2.1 的 Ubuntu），核心就一行：

```java
DownloadTask task = client.download(Path.of("xxx.torrent"),
    DownloadOptions.defaults().targetDir(Path.of("downloads")));
DownloadResult result = task.future().join();  // 阻塞到完成；异步则 thenAccept(...)
```

要点：多 tracker 种子自动逐层轮换；`udp://` 地址自动走 UDP tracker（BEP 15），无需配置；下载中互惠上传自动进行（见 4.4）。

### 4.2 磁力链接

#### 4.2.1 使用磁力下载（种子里有 tracker）

**依赖**：仅 core。

```java
MagnetUri magnet = MagnetUri.parse(
    "magnet:?xt=urn:btih:<40位hex或32位base32>&dn=显示名&tr=http://tk/announce");
DownloadTask task = client.download(magnet,
    DownloadOptions.defaults().targetDir(Path.of("downloads")));
```

机制（自动完成，无需干预）：连 Peer → 协商扩展协议（BEP 10）→ 从 Peer 拉取种子元数据（BEP 9）→ **SHA-1 与磁力里的哈希比对，不符换源** → 转入正常下载。元数据阶段 `state()==QUEUED`、进度 0，属正常。

#### 4.2.2 磁力从哪来？——从 .torrent 生成

磁力本质上就是 `info-hash + 可选 tracker 表`。三种获得方式：

**方式 A（推荐，不写代码）**：用现有工具，如 `transmission-show xx.torrent` 或 qBittorrent 界面的"复制磁力链接"。

**方式 B（代码生成，自包含无依赖）**：

```java
import java.nio.file.*; import java.security.*; import java.util.HexFormat;

public class Magnet {
    /** 从 .torrent 字节提取 info 字典并计算磁力哈希 */
    public static String fromTorrent(byte[] torrent, String... trackers) throws Exception {
        byte[] marker = "4:info".getBytes();
        outer: for (int i = 0; i < torrent.length - marker.length; i++) {
            for (int j = 0; j < marker.length; j++)
                if (torrent[i + j] != marker[j]) continue outer;
            int start = i + 6;                       // 指向 info 的 'd'
            int depth = 0;                            // 扫描 bencode 配对找字典结尾
            for (int k = start; k < torrent.length; k++) {
                int b = torrent[k] & 0xFF;
                if (b == 'd' || b == 'l') depth++;
                else if (b == 'i') { while (torrent[k] != 'e') k++; }
                else if (b >= '0' && b <= '9') { while (torrent[k] != ':') k++; }
                else if (b == 'e' && --depth == 0) {
                    byte[] hash = MessageDigest.getInstance("SHA-1")
                        .digest(java.util.Arrays.copyOfRange(torrent, start, k + 1));
                    StringBuilder sb = new StringBuilder("magnet:?xt=urn:btih:")
                        .append(HexFormat.of().formatHex(hash));
                    for (String tr : trackers) sb.append("&tr=").append(tr);
                    return sb.toString();
                }
            }
        }
        throw new IllegalArgumentException("no info dict");
    }
}
// 用法：Magnet.fromTorrent(Files.readAllBytes(Path.of("xx.torrent")), "http://tk/announce")
```

#### 4.2.3 无 tracker 的磁力（此时才需要 dht 包）

**依赖**：core **+ javathunder-dht**：

```xml
<dependency>
    <groupId>io.github.oatelauser</groupId>
    <artifactId>javathunder-dht</artifactId>
    <version>0.2.0</version>
</dependency>
```

```java
import io.github.oatelauser.thunder.dht.DhtPeerDiscovery;

try (TorrentClient client = DefaultTorrentClient.builder()
        .peerDiscovery(DhtPeerDiscovery.create())       // 公网自举节点
        .build()) {
    // 此时磁力里可以没有 &tr= ——Peer 发现走 DHT
    DownloadTask task = client.download(MagnetUri.parse("magnet:?xt=urn:btih:<hash>"), options);
}
// 内网/隔离环境：DhtPeerDiscovery.create(List.of("seed-host.lan:6881")) 自建自举
```

### 4.3 多文件种子 / 整个目录

**依赖**：仅 core。无需任何特殊代码：

```java
DownloadResult r = client.download(Path.of("model-x.torrent"), options).future().join();
// r.file() 指向目录，例如 downloads/model-x/{weights.bin, tokenizer.json, config/...}
```

恶意种子的路径穿越（`..`、盘符、保留名等）已被解析层拒绝，不会逃出 targetDir。

### 4.4 做种（= 别人怎么从你这里下载）

**依赖**：仅 core。核心认知：**上传不需要写代码，下载器下载的同时就自动上传**。"做种"只是让节点完整持有数据并保持在线。

**4.4.1 下载完成后继续做种**（最常用——下载器自动变成新的分发源）：

```java
DownloadOptions seedOptions = new DownloadOptions(
    Path.of("downloads"), /*resume*/ true, /*verify*/ true,
    /*seedAfterComplete=*/ true,          // ← 关键：完成后不退出，转 SEEDING 持续上传
    /*↓限*/ 0, /*↑限*/ 0, RestartVerifyMode.FULL);

DownloadTask task = client.download(torrentPath, seedOptions);
task.future().join();                      // future 完成 ≠ 做种结束
// task.state() == TaskState.SEEDING；想停：task.cancel(false) 或 client.close()
```

**4.4.2 我要做"原始种子源"**（内网分发的第一台机器）：先用 4.5 造种子 → 在源机器上以上述 `seedAfterComplete` 跑一次完整下载（或从零下载一次）→ 保持进程在线，它就是 Seed。之后每台目标机器下载完成也会自动成为种子源（这正是 P2P 分发扩展的原理）。

**4.4.3 从本地已有文件直接做种**：0.2.0 的推荐路径是"完整下载一次后转做种"；如需跳过下载直接对已有数据做种，属高级用法——预填 `.part` 与 `.jt-resume` 状态文件（格式见 DESIGN §5.7，参考测试 `RestartVerifyModeTest` 里的预填代码）。公共 API 化的"导入已有文件"在路线图中。

### 4.5 生成种子（你要分发自己的文件给别人）

**依赖**：core **+ javathunder-testkit**（生成器在 testkit）。

```java
import io.github.oatelauser.thunder.testkit.TorrentGenerator;

// 单文件：生成长度 50MB 的随机内容 + 配套 .torrent（tracker 指向你的 announce）
var single = TorrentGenerator.generate(dir, "dataset.bin", 50_000_000,
    "http://tracker.lan:6969/announce", new Random());
// single.contentFile() = 数据文件本体；single.torrentFile() = 分发给他人的种子

// 多文件（目录树，大模型典型形态）：
var multi = TorrentGenerator.generateMultiFile(dir, "model-x", java.util.List.of(
        java.util.List.of(java.util.List.of("weights.safetensors"), 3_000_000_000L),
        java.util.List.of(java.util.List.of("tokenizer.json"), 2_000_000L),
        java.util.List.of(java.util.List.of("conf"), java.util.List.of("config.yaml"), 5_000L)),
    4 * 1024 * 1024,                          // pieceLength：大文件建议 4MB
    "http://tracker.lan:6969/announce", new Random());
```

### 4.6 内网镜像分发完整拓扑（大模型场景）

**依赖**：源/目标机器 core；（可选去 tracker）+ dht；（源机器造种子）+ testkit。

```text
          ┌──────────── ① 种子分发（任意途径：内网 HTTP/IM/配置中心）────────────┐
          │                                                                    │
 源机器 seed-01                       目标机器 ×N（同一份种子文件）              │
 ┌─────────────────────┐             ┌─────────────────────┐                   │
 │ 造种子(4.5) + 下载转 │   ② tracker │ download +           │ ◀─────────────────┘
 │ 做种(4.4.2) 持续在线 │◀──announce─▶│ seedAfterComplete    │
 └─────────────────────┘  (或 DHT)   └─────────────────────┘
```

```java
// ② 的 tracker 二选一：
//    HTTP：内网跑一个 tracker 程序（opentracker 等），种子 announce 填它
//    UDP ：announce 填 udp://tracker.lan:6969/announce —— 引擎自动走 UDP，零配置
//    去 tracker：两端都 builder().peerDiscovery(DhtPeerDiscovery.create(List.of("seed-01.lan:6881")))
// ③ 目标机器统一配置建议（大体积镜像）：
DownloadOptions mirrorOptions = DownloadOptions.defaults()
    .targetDir(Path.of("/data/mirrors"))
    .restartVerify(RestartVerifyMode.SAMPLED)   // TB 级断点恢复：抽样校验，不重扫全盘
    .rateLimits(0, 0);
```

运维事实（实测口径）：N 台目标互相取缺（rarest-first），不都挤源机器；进程重启同目录重新 download 自动续传。

### 4.7 断点续传（自动）与重启校验三档

**依赖**：仅 core。续传无需代码——同 `targetDir` 重新 `download` 即从进度位图继续。三档决定"重启时多信任上次的进度"：

```java
options.restartVerify(RestartVerifyMode.FULL)     // 默认：每件重校验（最稳，大文件慢）
options.restartVerify(RestartVerifyMode.SAMPLED)  // 抽 10%+首末件（大镜像推荐）
options.restartVerify(RestartVerifyMode.NONE)     // 全信任位图（最快；漏检坏件做种时自愈）
```

进度文件 `<name>.jt-resume` 与数据 `.part` 同目录；删除两者 = 强制从头下。

### 4.8 限速（全局 × 任务，双向独立）

**依赖**：仅 core。

```java
// 全局（client 级、跨任务）：
DefaultTorrentClient.builder()
    .downloadLimitBytesPerSecond(10 * 1024 * 1024)   // 10MB/s；0=不限（默认）
    .uploadLimitBytesPerSecond(2 * 1024 * 1024);

// 任务级（与全局串联，两者都要放行）：
options.rateLimits(/*↓*/ 512 * 1024, /*↑*/ 64 * 1024);
```

### 4.9 监控 / 事件（给自己的面板或指标系统挂回调）

**依赖**：仅 core。7 个回调全部可选（default 方法），完整清单见 §5 速查：

```java
task.addListener(new TaskListener() {
    @Override public void onStateChanged(TaskState from, TaskState to) {
        System.out.println("状态: " + from + " -> " + to);
    }
    @Override public void onProgress(ProgressSnapshot p) {         // ~500ms 一帧
        ui.update(p.fraction(), p.downloadRateBps(), p.etaMillis());
    }
    @Override public void onPieceComplete(int pieceIndex) { }      // 想画"分片位图"用这个
    @Override public void onTrackerAnnounce(String url, String fail, int seed, int leech) { }
    @Override public void onPeerConnected(String addr) { }
    @Override public void onPeerDisconnected(String addr, String reason) { }
    @Override public void onError(Throwable t) { }
});
// 不注册也能随时主动拉快照：task.snapshot()
```

### 4.10 暂停 / 恢复 / 取消 / 关闭

**依赖**：仅 core。

```java
task.pause();              // 停止请求、保连接上下文 → PAUSED
task.resume();             // 继续
task.cancel(false);        // 删任务，保留已下数据与断点
task.cancel(true);         // 任务+本地数据+状态文件全删
client.close();            // 停一切（AutoCloseable，幂等）
```

### 4.11 传输引擎：默认与 NIO（什么时候需要关心）

**依赖**：仅 core。默认使用**阻塞传输**（简单稳健）；高吞吐场景切换 **NIO 事件循环**（回环实测 110MB/s vs 35MB/s）：

```java
// 方式一：系统属性（不改代码）
//   java -Djavathunder.transport=nio YourApp
// 方式二：显式（差分/调试）
DefaultTorrentClient.builder()
    .transportFactory(NioTransport::new)
    .build();
```

不确定就用默认；确认带宽瓶颈在引擎侧再切 NIO。

### 4.12 回调跑在你自己的线程上（如 UI 线程）

**依赖**：仅 core。

```java
DefaultTorrentClient.builder()
    .listenerExecutor(java.awt.EventQueue::invokeLater)   // Swing 示例；也可换队列/exec
    .build();
```

### 4.13 给自己的项目写集成测试

**依赖**：core（test）+ javathunder-testkit（test）。完整可抄模板见 testkit 的
`LoopbackAcceptanceTest`；三件套 = EmbeddedTracker + TorrentGenerator + FakeSeeder，
另可用 `-Djavathunder.transport=nio` 让同一测试双传输各跑一遍（差分）。

---

## 第 5 章 API 速查

**api 模块（稳定契约）**

| 类/接口 | 常用成员 | 一句话 |
|---|---|---|
| `TorrentClient` | `download(Path/MagnetUri, options)` `close()` | 门面 |
| `DownloadTask` | `future()` `state()` `snapshot()` `pause()` `resume()` `cancel(boolean)` `addListener(...)` | 任务句柄 |
| `DownloadOptions` | `defaults()` `targetDir()` `rateLimits()` `restartVerify()` | 单任务配置 |
| `MagnetUri` | `parse(String)` | 磁力解析 |
| `TaskListener` | onStateChanged/onProgress/onPieceComplete/onTrackerAnnounce/onPeerConnected/onPeerDisconnected/onError | 事件 |
| `ProgressSnapshot` | fraction/rates/connectedPeers/availability/etaMillis | 快照 |
| `TaskState` | QUEUED→VERIFYING→DOWNLOADING→SEEDING/COMPLETED；PAUSED/FAILED/CANCELLED | 状态机 |
| `RestartVerifyMode` | FULL/SAMPLED/NONE | 重启校验档 |
| `PeerDiscoverySource` | `getPeers(infoHash)` | 去 tracker 发现 SPI |

**Builder（DefaultTorrentClient.builder()）**：`listenPort(6881)` `maxConcurrentTasks(3)` `maxPeersPerTask(50)` `download/uploadLimitBytesPerSecond(0)` `listenerExecutor(...)` `peerDiscovery(...)` `transportFactory(...)`

**dht 模块**：`DhtPeerDiscovery.create()` / `create(List<String> 内网自举)`

---

## 第 6 章 架构 30 秒

```
api（接口契约） ← core（引擎：bencode/种子解析/HTTP+UDP tracker/线协议/
                  存储单+多文件/调度/choking/限速/事件 + 双传输实现）
                  ← dht（可选，经 PeerDiscoverySource SPI 注入）
                  ← testkit（造种子/内嵌 tracker/测试对端）· cli（示例）
```

线程模型：NIO 时一个 selector 平台线程管全部连接 I/O；磁盘与哈希在虚拟线程池；事件回调在独立事件线程。深入读 [DESIGN.md](DESIGN.md)（需求与详设）与 [ADR](adr/)（三份关键决策记录）。

## 第 7 章 协议兼容（BEP）速查

实现：BEP 3(v1+多文件)/9/10/11/12/15/20/23/27 完整，BEP 5 查询模式（可选模块）；
容忍解码：BEP 6；未实现：BEP 52(v2)。互操作实测：ttorrent 双向 + 公网 Ubuntu（[INTEROP.md](INTEROP.md)）。

## 第 8 章 部署要点

- 端口：默认 TCP 6881 入站（放行可显著提升互惠上传）；tracker/DHT 为出站
- 磁盘：`<name>.part` + `<name>.jt-resume`；完成自动改名/落位
- 日志：slf4j（记得带后端）；DEBUG 级有 peer/tracker/坏件全量轨迹
- 量级：≤1000 连接、单任务 ≤200 Peer；无 MSE 加密；Windows 做种期 `.part` 名

## 第 9 章 常见问题排错

| 现象 | 原因与处理 |
|---|---|
| 完全没速度、peers=0 | 种子里 tracker 失效且未引 DHT（§4.2.3）；或 6881 未放行只影响上传不影响连出 |
| 磁力任务一直 QUEUED | 没有 tracker 也没注入 peerDiscovery；或当前网络取不到元数据持有者 |
| 没有任何日志 | 缺 slf4j 后端（§2.0 第二个依赖） |
| 速度低于预期 | 先确认带宽/对端；引擎侧再试 `-Djavathunder.transport=nio`（§4.11） |
| 重启后从头下载 | `targetDir` 变了，或 `.jt-resume` 被删；续传要求同目录 |
| Windows 做种时文件叫 `.part` | 已知限制（句柄占用），完成/停止后改名 |
