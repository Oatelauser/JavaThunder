# JavaThunder 使用手册

版本：v0.2.0 · 坐标：`io.github.oatelauser` · 要求：JDK 21+

**怎么读这本手册**：第 1 章建立正确的心智模型（角色、上传下载的真实关系、"机器越多越快"的原理）；第 2 章是完整可运行的快速入门；第 3 章回答"什么时候引哪个包"；**第 4 章下载场景 / 第 5 章上传与分发场景**按你的意图二选一进入；第 6 章两类共用；第 7–11 章是速查与排错。

---

## 第 1 章 心智模型：先澄清概念

### 1.1 有没有"服务端和客户端"？

**没有传统意义上的服务端和客户端。** 这是 P2P 协议——每个 JavaThunder 实例都是**对等节点（Peer）**，**既是客户端又是服务端**：既能从别人那里拉数据，也随时应答别人发来的数据请求。你只需区分四种角色：

| 角色 | 是什么 | 谁来充当 | 你必须部署吗 |
|---|---|---|---|
| **下载者（Leecher）** | 还没拿全数据的节点；**边下边传** | 你的应用 | — |
| **做种者（Seed）** | 拿全数据的节点，只上传 | 任何完整持有数据的节点（**含下载完成的下载者**） | 分发场景需要至少 1 个 |
| **Tracker** | "电话簿"：告诉节点彼此的地址。**不碰文件数据** | 任何一个 HTTP/UDP tracker 程序 | 不必须（种子里有地址就用；可被 DHT 替代） |
| **DHT 网络** | 去 tracker 化的分布式电话簿 | 所有参与的节点 | 不必须（可选模块） |

### 1.2 上传和下载的真实关系（重要）

上传不是和下载平行的另一件事，而是**每个节点始终具备的能力**，分两种形态：

| 形态 | 什么时候发生 | 你要写的代码 |
|---|---|---|
| **互惠上传** | 你下载的过程中，别人向你请求你**已持有的分片**——引擎自动应答（tit-for-tat：谁给你传得多你优先回馈谁） | 零 |
| **做种** | 你**持有全部数据**后继续在线供下载 | 只需一个开关 `seedAfterComplete=true`，保持进程运行 |

**不存在"上传服务/上传接口"这种东西**——同一条 TCP 连接上的两个方向，引擎自动双向处理。所谓"做种"只是"完整持有 + 持续在线"的状态。

### 1.3 发现面与数据面：tracker / DHT / PEX 三条电话簿

```
                    发现面（只交换地址，三条渠道可混用、自动叠加）
   下载方 ──────────▶ tracker（中心电话簿，HTTP/UDP）
          ──────────▶ DHT（分布式电话簿，去中心化）
          ──────────▶ PEX（已连接的节点互相介绍新节点）
                    数据面（文件字节只走这一条路）
   下载方 ◀────────── 点对点 TCP 直连 ──────────▶ 数据持有者（任何 Peer）
```

**发现面从不传输文件数据**——它们只回答"谁的机器上有这个文件？给我地址列表"。拿到地址后，数据永远是节点间直连拉取。

**DHT 能否替代 tracker？** 在"找 Peer"这个核心功能上**完全等价**——所以不存在"离线环境必须用 DHT、联网环境必须用 tracker"的分界。真正的区分维度是**中心化/去中心化**，按需求选型：

| 维度 | Tracker（中心） | DHT（去中心） |
|---|---|---|
| 找 Peer（核心功能） | ✅ | ✅ 完全等价 |
| 冷启动 | announce 一次即得列表 | 需自举节点引路、逐步探索 |
| 可控性（内网） | ✅ 自部署，列表/统计自己说了算 | 纯内网必须**自建自举节点**，网络要自己长起来 |
| 可观测 | ✅ 中心有全量视图，好排障 | 无中心，谁也没有全貌 |
| 维护成本 | 一个轻量常驻进程 | 零部署 |
| 单点风险 | 中心挂了发现面断（已连 Peer 不受影响） | 无单点 |
| 网络适配 | HTTP/UDP 出站，几乎全通 | UDP 出站，严格网络可能被拦 |

**选型速记**：

- **内网镜像分发**（机器都归你管，要可控可观测）→ 自建 tracker 首选，DHT 需额外自举不划算
- **公网生态**（tracker 随时可能失效）→ DHT + PEX 兜底
- **实际总是混用**：引擎自动把种子内 tracker、DHT、PEX 三路候选叠加（本库默认行为）

**PEX 是第三条腿**：节点间互相介绍节点，不依赖前两者、已有连接即可扩散——小规模内网里往往比 DHT 更实用。本库 PEX 自动开启（private 种子除外）。

### 1.4 纠正一个常见误解：没有"分片上传到不同服务器"

BitTorrent **不是**"把文件切片分散存到多个服务器、下载时从各服务器拼回来"（那是对象存储/纠删码的模型）。对照：

| | 分片存储模型（误解） | BitTorrent（实际） |
|---|---|---|
| 分片存在哪 | 分散上传到多个**服务**上 | **没有"服务"存分片**。每个 Peer 本地持有（或将持有）**完整文件** |
| 上传是什么 | 把分片推送给服务 | 别人直连**你的机器**，按需请求你手中的某几片 |
| 怎么组装 | 从不同服务器各取一片最后拼接 | 收到的块**按字节偏移直接写进本地目标文件**——`.part` 从第一天就是全尺寸预分配，片到位就写到位，不存在"最后合成"一步 |

### 1.5 为什么机器越多、总吞吐越大（P2P 的核心价值）

每台下载机**同时是消费者和新的供给者**——它刚下到的分片立刻可供别人拉取（rarest-first 调度还专门优先拉全网最稀少的分片，让分片尽快扩散）。对照传统 C/S 分发：

| | C/S（HTTP 下载/对象存储） | P2P（BitTorrent） |
|---|---|---|
| 供给者 | 只有源服务器 | 源 + 每一台已下到数据的机器 |
| 100 台机器同时拉 50GB | 全部挤源机器，出口带宽被 100 份瓜分 | 100 台互为源，**总量越大供给越多**，源机器压力近似恒定 |
| 规模化的瓶颈 | 源机器带宽（线性恶化） | 边际递减——新人既增加需求也增加供给 |

```
1 → N 分发时的分片流动（rarest-first 保证分散）：
   seed-01 ─┬─▶ 目标机 A ─┬─▶ 目标机 C（从 A 拿它缺的片）
            ├─▶ 目标机 B ─┘
            └─▶ 目标机 C ──▶ 目标机 B（从 C 拿它缺的片）
   下完的机器自动转做种 ⇒ 供给随规模自然增长
```

这正是内网镜像分发（§5.4）选 P2P 的理由：分发节点越多，整体越快，而不是越挤越慢。

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

> **非 Maven/Gradle 用户**：每个库模块都附带 `javathunder-<module>-0.2.0-with-dependencies.jar`（已含全部传递依赖；slf4j 后端按惯例仍由你的应用自选）。单 jar 即可编译运行：
> ```bash
> java -cp javathunder-core-0.2.0-with-dependencies.jar QuickStart.java
> ```
> 不带分类器的主 jar 保持瘦 jar 供构建工具做依赖解析——不要把 fat jar 当依赖引入。

### 2.1 版本一（推荐第一跑）：零网络本地 Swarm——离线也能完整跑通

**适用**：任何环境（无外网/公司隔离网络/CI）。全程在本机完成"下载→SHA-1 校验→完成"，约 1 秒。
**需要额外引入 `javathunder-tools`**（它不只是测试工具，也用于造种子、搭本地小 swarm；
`EmbeddedTracker` 由传递依赖 `javathunder-tracker` 提供）：

```xml
<dependency>
    <groupId>io.github.oatelauser</groupId>
    <artifactId>javathunder-tools</artifactId>
    <version>0.2.0</version>
</dependency>
```

```java
import io.github.oatelauser.thunder.api.*;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.testkit.*;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class OfflineQuickStart {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of("sandbox");
        Files.createDirectories(dir);

        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {               // ① 电话簿（真实 HTTP tracker，回环）
            var gen = TorrentGenerator.generate(dir, "hello.bin", 1_000_000,     // ② 造种子+内容
                tracker.announceUrl(), new Random(42));
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(gen.torrentFile()));

            try (FakeSeeder seeder = FakeSeeder.start(gen.contentFile(), meta)) { // ③ 种子源（真实线协议 TCP 服务，回环）
                seeder.announceTo(tracker);
                try (TorrentClient client = DefaultTorrentClient.builder().build()) {  // ④ 下载方（你的业务代码）
                    DownloadTask task = client.download(gen.torrentFile(),
                        DownloadOptions.defaults().targetDir(dir.resolve("out")));
                    task.addListener(new TaskListener() {
                        @Override public void onProgress(ProgressSnapshot p) {
                            System.out.printf("progress %.1f%%  ↓%dKB/s  peers=%d%n",
                                p.fraction() * 100, p.downloadRateBps() / 1024, p.connectedPeers());
                        }
                    });
                    DownloadResult r = task.future().get(30, java.util.concurrent.TimeUnit.SECONDS);
                    System.out.printf("完成: %s (%d 字节)%n", r.file(), r.bytes());
                }
            }
        }
    }
}
```

**预期行为**：数秒内打印若干 `progress ...` 行，最后输出 `完成: sandbox\out\hello.bin (1000000 字节)`。
离线 ≠ 模拟：①③ 是**协议级真实实现**（EmbeddedTracker 是真的 HTTP tracker，FakeSeeder 是真的说 BitTorrent 线协议的 TCP 服务），只是都跑在本机回环——跑通的代码和公网下载是**同一份**。可执行版本见 tools 模块的 `OfflineQuickStartTest`（IDEA 直接运行）。

### 2.2 版本二（可选）：下载公网真实种子（Ubuntu 官方 ISO）

**适用**：有国际网络的环境（tracker 与对端多在境外，国内直连可能连不上 Peer 或极慢——属网络状况而非引擎故障）。
**依赖**：仅 core（2.0 已给）。

```bash
curl -fSL -o ubuntu.torrent https://releases.ubuntu.com/24.04/ubuntu-24.04.4-desktop-amd64.iso.torrent
```

```java
// QuickStart.java —— 注意：Ubuntu ISO 约 6GB，本例目标是"90 秒验证集成"，不是下完整
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
                    // 注意用 %n 换行：\r 不触发流刷新，IDEA/JUnit 控制台里会一行都看不到
                    System.out.printf("progress %.2f%%  ↓%dKB/s  peers=%d  eta=%ss%n",
                        p.fraction() * 100, p.downloadRateBps() / 1024,
                        p.connectedPeers(), p.etaMillis() == null ? "-" : p.etaMillis() / 1000);
                }
            });

            // fraction 是字节级进度（含未凑齐分片的已收块），块一到就会动；
            // 完整下完需数小时——要下到底就换 task.future().join()：
            try {
                DownloadResult result = task.future().get(90, java.util.concurrent.TimeUnit.SECONDS);
                System.out.printf("完成: %s (%d 字节)%n", result.file(), result.bytes());
            } catch (java.util.concurrent.TimeoutException verificationDone) {
                System.out.println("集成验证通过（进度事件流正常），已取消；断点已保留。");
                task.cancel(false);
            }
        }
    }
}
```

**预期行为**：数秒内 `progress` 行开始刷新（`peers=0` 只是还没连上对端，帧照常到达）。命令行等价物：`java -jar javathunder-cli.jar download ubuntu.torrent --dir downloads`。

---

## 第 3 章 依赖引入指南：什么时候引哪个包

| 你的需求 | 需要引入 | scope |
|---|---|---|
| 下载 .torrent / 磁力（种子里有 tracker）/ 做种 / 限速 / 事件 | `javathunder-core`（+slf4j 后端） | compile |
| 磁力链接**且没有 tracker**，或完全去 tracker 分发 | **再加** `javathunder-dht` | compile（不需要时别引，轻量） |
| 造种子 / 本地测试 swarm / 集成测试对端 | **再加** `javathunder-tools` | test（运维脚本可用 compile） |
| 内网自建 tracker（可执行 jar 直跑） | `javathunder-tracker` | 独立部署 |
| 想要命令行工具演示 | `javathunder-cli` | 直接运行 jar |

判断规则一句话：**默认只引 core；只有"去 tracker"才引 dht；只有"造种子/自测"才引 tools；自建 tracker 用 tracker 模块。**

---

## 第 4 章 下载场景：我要获取文件

> 以下延续 2.0 依赖；每节开头"**依赖**"行说明额外要引什么。**下载过程中你同时在为别人上传**（互惠上传，见 §4.6）——这是自动的。

### 4.1 下载网上的种子文件（最常见）

**依赖**：仅 core。

从任何网站下载 `.torrent` 文件后（就像 2.2 的 Ubuntu），核心就一行：

```java
DownloadTask task = client.download(Path.of("xxx.torrent"),
    DownloadOptions.defaults().targetDir(Path.of("downloads")));
DownloadResult result = task.future().join();  // 阻塞到完成；异步则 thenAccept(...)
```

要点：多 tracker 种子自动逐层轮换；`udp://` 地址自动走 UDP tracker（BEP 15），无需配置。

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

### 4.4 断点续传（自动）与重启校验三档

**依赖**：仅 core。续传无需代码——同 `targetDir` 重新 `download` 即从进度位图继续。三档决定"重启时多信任上次的进度"：

```java
options.restartVerify(RestartVerifyMode.FULL)     // 默认：每件重校验（最稳，大文件慢）
options.restartVerify(RestartVerifyMode.SAMPLED)  // 抽 10%+首末件（大镜像推荐）
options.restartVerify(RestartVerifyMode.NONE)     // 全信任位图（最快；漏检坏件做种时自愈）
```

进度文件 `<name>.jt-resume` 与数据 `.part` 同目录；删除两者 = 强制从头下。

### 4.5 限速（全局 × 任务，双向独立）

**依赖**：仅 core。

```java
// 全局（client 级、跨任务）：
DefaultTorrentClient.builder()
    .downloadLimitBytesPerSecond(10 * 1024 * 1024)   // 10MB/s；0=不限（默认）
    .uploadLimitBytesPerSecond(2 * 1024 * 1024);

// 任务级（与全局串联，两者都要放行）：
options.rateLimits(/*↓*/ 512 * 1024, /*↑*/ 64 * 1024);
```

### 4.6 下载时你同时在帮别人下载（互惠上传）

**依赖**：仅 core。**没有任何代码要写**——这一节只是让你知道发生了什么：你已持有的分片会被请求方拉取；引擎按 tit-for-tat 优先回馈给你贡献最多的节点（外加定期给新节点机会的"乐观槽"）。它带来的实际效果：

- 公网下载：你的节点"有来有往"，更容易被对端持续 unchoke，**下载速度通常更好**
- 内网分发：每台下载机自动成为分片的中继供给者——**这就是 §1.5"机器越多越快"的机制落地**
- 想抑制它：§4.5 的上传限速；想贡献更多：完成后转做种（§5.1）

---

## 第 5 章 上传与分发场景：我要把文件给别人

> 核心认知（§1.2）：**上传不需要写代码**——上传能力始终在线；你要做的是"完整持有数据 + 保持进程在线 + 让别人能发现你"。

### 5.1 做种三式

**依赖**：仅 core。

**5.1.1 下载完成后继续做种**（最常用——下载器自动变成新的分发源）：

```java
DownloadOptions seedOptions = new DownloadOptions(
    Path.of("downloads"), /*resume*/ true, /*verify*/ true,
    /*seedAfterComplete=*/ true,          // ← 关键：完成后不退出，转 SEEDING 持续上传
    /*↓限*/ 0, /*↑限*/ 0, RestartVerifyMode.FULL);

DownloadTask task = client.download(torrentPath, seedOptions);
task.future().join();                      // future 完成 ≠ 做种结束
// task.state() == TaskState.SEEDING；想停：task.cancel(false) 或 client.close()
```

**5.1.2 我要做"原始种子源"**（内网分发的第一台机器）：先用 5.2 造种子 → 在源机器上以 `seedAfterComplete` 跑一次完整下载 → 保持进程在线，它就是 Seed。之后每台目标机器下载完成也会自动成为种子源（§1.5 的供给增长）。

**5.1.3 从本地已有文件直接做种**：0.2.0 的推荐路径是"完整下载一次后转做种"；跳过下载直接对已有数据做种属高级用法——预填 `.part` 与 `.jt-resume` 状态文件（格式见 DESIGN §5.7，参考测试 `RestartVerifyModeTest` 里的预填代码）。公共 API 化的"导入已有文件"在路线图中。

### 5.2 生成种子（分发的第一步）

**依赖**：core **+ javathunder-tools**（生成器在 tools，Java 包名保留 `...thunder.testkit`）。

```java
import io.github.oatelauser.thunder.testkit.TorrentGenerator;

// 单文件：生成内容 + 配套 .torrent（tracker 指向你的 announce）
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

### 5.3 别人怎么找到你：发现渠道的选择

**依赖**：tracker/内嵌 tracker → 仅 core（自建 tracker 用 `javathunder-tracker` 模块：生产级 `TrackerServer` 或内嵌 `EmbeddedTracker`）；去 tracker → + dht。

回顾 §1.3：发现面只交换地址。三条渠道可混用，引擎自动叠加：

| 渠道 | 适用 | 你要做的 |
|---|---|---|
| **HTTP Tracker**（种子里写 announce） | 公网种子默认；内网可跑本项目 `javathunder-tracker`（`java -jar javathunder-tracker-*-with-dependencies.jar --port 6881`，opentracker 替代）或 tools 的 `EmbeddedTracker`（进程内嵌） | 种子生成时填 announce 地址 |
| **UDP Tracker**（BEP 15） | 同上，UDP 更省开销 | announce 填 `udp://...`，引擎自动分派 |
| **DHT**（可选模块） | 完全去 tracker；内网自建自举 | 两端 `builder().peerDiscovery(DhtPeerDiscovery.create(...))` |
| PEX | 已连接的节点互相介绍新节点 | 无需配置，自动 |

### 5.4 内网镜像分发完整拓扑（大模型场景）

**依赖**：源/目标机器 core；（可选去 tracker）+ dht；（源机器造种子）+ tools；tracker 机器（若有）javathunder-tracker 可执行 jar。

```text
          ┌──────────── ① 种子分发（任意途径：内网 HTTP/IM/配置中心）────────────┐
          │                                                                    │
 源机器 seed-01                       目标机器 ×N（同一份种子文件）              │
 ┌─────────────────────┐             ┌─────────────────────┐                   │
 │ 造种子(5.2) + 下载转 │   ② 发现面 │ download +           │ ◀─────────────────┘
 │ 做种(5.1.2) 持续在线 │◀──tracker──▶│ seedAfterComplete    │
 └─────────────────────┘  (或 DHT)   └──────────┬──────────┘
        ▲                                        │ ③ 数据面：N 台目标
        └──────────── 点对点互拉分片 ◀───────────┘────────── 互相取缺，不都挤源机器
```

```java
// ② 的发现面三选一：
//    HTTP tracker：内网跑一个 tracker 程序（opentracker 等），种子 announce 填它
//    UDP tracker ：announce 填 udp://tracker.lan:6969/announce —— 引擎自动走 UDP，零配置
//    去 tracker  ：两端都 builder().peerDiscovery(DhtPeerDiscovery.create(List.of("seed-01.lan:6881")))
// ③ 目标机器统一配置建议（大体积镜像）：
DownloadOptions mirrorOptions = DownloadOptions.defaults()
    .targetDir(Path.of("/data/mirrors"))
    .restartVerify(RestartVerifyMode.SAMPLED)   // TB 级断点恢复：抽样校验，不重扫全盘
    .rateLimits(0, 0);
```

运维事实（实测口径）：N 台目标互相取缺（rarest-first），下完自动转做种、供给随规模增长（§1.5）；进程重启同目录重新 download 自动续传。

---

## 第 6 章 两类共用：监控、生命周期与工程选项

### 6.1 监控 / 事件（给自己的面板或指标系统挂回调）

**依赖**：仅 core。7 个回调全部可选（default 方法），完整清单见 §7 速查：

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

### 6.2 暂停 / 恢复 / 取消 / 关闭

**依赖**：仅 core。

```java
task.pause();              // 停止请求、保连接上下文 → PAUSED
task.resume();             // 继续
task.cancel(false);        // 删任务，保留已下数据与断点
task.cancel(true);         // 任务+本地数据+状态文件全删
client.close();            // 停一切（AutoCloseable，幂等）
```

### 6.3 传输引擎：默认与 NIO（什么时候需要关心）

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

### 6.4 回调跑在你自己的线程上（如 UI 线程）

**依赖**：仅 core。

```java
DefaultTorrentClient.builder()
    .listenerExecutor(java.awt.EventQueue::invokeLater)   // Swing 示例；也可换队列/exec
    .build();
```

### 6.5 给自己的项目写集成测试

**依赖**：core（test）+ javathunder-tools（test）。完整可抄模板见 tools 模块的
`LoopbackAcceptanceTest`；三件套 = EmbeddedTracker（传递依赖 tracker 模块提供）
+ TorrentGenerator + FakeSeeder，
另可用 `-Djavathunder.transport=nio` 让同一测试双传输各跑一遍（差分）。

---

## 第 7 章 API 速查

**api 模块（稳定契约）**

| 类/接口 | 常用成员 | 一句话 |
|---|---|---|
| `TorrentClient` | `download(Path/MagnetUri, options)` `close()` | 门面 |
| `DownloadTask` | `future()` `state()` `snapshot()` `pause()` `resume()` `cancel(boolean)` `addListener(...)` | 任务句柄 |
| `DownloadOptions` | `defaults()` `targetDir()` `rateLimits()` `restartVerify()` | 单任务配置 |
| `MagnetUri` | `parse(String)` | 磁力解析 |
| `TaskListener` | onStateChanged/onProgress/onPieceComplete/onTrackerAnnounce/onPeerConnected/onPeerDisconnected/onError | 事件 |
| `ProgressSnapshot` | fraction(字节级)/rates/connectedPeers/availability/etaMillis | 快照 |
| `TaskState` | QUEUED→VERIFYING→DOWNLOADING→SEEDING/COMPLETED；PAUSED/FAILED/CANCELLED | 状态机 |
| `RestartVerifyMode` | FULL/SAMPLED/NONE | 重启校验档 |
| `PeerDiscoverySource` | `getPeers(infoHash)` | 去 tracker 发现 SPI |

**Builder（DefaultTorrentClient.builder()）**：`listenPort(6881)` `maxConcurrentTasks(3)` `maxPeersPerTask(50)` `download/uploadLimitBytesPerSecond(0)` `listenerExecutor(...)` `peerDiscovery(...)` `transportFactory(...)`

**dht 模块**：`DhtPeerDiscovery.create()` / `create(List<String> 内网自举)`

**tracker 模块**：`EmbeddedTracker.start()/start(port)/start(port, interval)`（内嵌，回环）
`TrackerServer.start(port, interval)`（生产，0.0.0.0，默认 6881/1800s）`stats()`（每
info-hash seeders/leechers）；可执行 jar 入口 `TrackerMain`（`--port` `--announce-interval`）

**tools**：`TorrentGenerator.generate/generateMultiFile` `FakeSeeder/NioSeeder.start` `MetadataSeeder.start`（BEP 9 对端）

---

## 第 8 章 架构 30 秒

```
api（接口契约） ← core（引擎：bencode/种子解析/HTTP+UDP tracker/线协议/
                  存储单+多文件/调度/choking/限速/事件 + 双传输实现）
                  ← dht（可选，经 PeerDiscoverySource SPI 注入）
                  ← tracker（内嵌/生产 HTTP tracker）· tools（造种子/测试对端）· cli（示例）
```

线程模型：NIO 时一个 selector 平台线程管全部连接 I/O；磁盘与哈希在虚拟线程池；事件回调在独立事件线程。深入读 [DESIGN.md](DESIGN.md)（需求与详设）与 [ADR](adr/)（三份关键决策记录）。

## 第 9 章 协议兼容（BEP）速查

实现：BEP 3(v1+多文件)/9/10/11/12/15/20/23/27 完整，BEP 5 查询模式（可选模块）；
容忍解码：BEP 6；未实现：BEP 52(v2)。互操作实测：ttorrent 双向 + 公网 Ubuntu（[INTEROP.md](INTEROP.md)）。

## 第 10 章 部署要点

- 端口：默认 TCP 6881 入站（放行可显著提升互惠上传——别人连不进你，就只能你连出）；tracker/DHT 为出站
- 磁盘：`<name>.part` + `<name>.jt-resume`；完成自动改名/落位
- 日志：slf4j（记得带后端）；DEBUG 级有 peer/tracker/坏件全量轨迹
- 量级：≤1000 连接、单任务 ≤200 Peer；无 MSE 加密；Windows 做种期 `.part` 名

## 第 11 章 常见问题排错

| 现象 | 原因与处理 |
|---|---|
| 完全没速度、peers=0 | 种子里 tracker 失效且未引 DHT（§4.2.3）；或 6881 未放行只影响上传不影响连出 |
| 磁力任务一直 QUEUED | 没有 tracker 也没注入 peerDiscovery；或当前网络取不到元数据持有者 |
| 没有任何日志 | 缺 slf4j 后端（§2.0 第二个依赖） |
| 速度低于预期 | 先确认带宽/对端；引擎侧再试 `-Djavathunder.transport=nio`（§6.3） |
| 重启后从头下载 | `targetDir` 变了，或 `.jt-resume` 被删；续传要求同目录 |
| Windows 做种时文件叫 `.part` | 已知限制（句柄占用），完成/停止后改名 |
| IDEA/JUnit 里运行完全没打印 | 进度 printf 用了 `\r` 不换行——缓冲流不刷新就一行都看不到；行尾改用 `%n`（§2.2 示例已修正） |
| 看似"卡住不动"其实在慢速下载 | 公网种子可能只连到 1 个 Peer、速率 KB/s 级（6GB 需数小时），`join()` 会一直阻塞。先用 §2.1 离线版验证集成，再给 future 加超时观察真实速率 |
