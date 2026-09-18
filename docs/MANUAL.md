# JavaThunder 使用手册

版本：v0.6.0 · 坐标：`io.github.oatelauser` · 要求：JDK 21+

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
        <version>0.6.0</version>
    </dependency>
    <!-- 日志后端：本库只依赖 slf4j-api，不带后端会静默无日志。示例用 simple，生产换 logback -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-simple</artifactId>
        <version>2.0.17</version>
    </dependency>
</dependencies>
```

> **非 Maven/Gradle 用户**：每个库模块都附带 `javathunder-<module>-0.6.0-with-dependencies.jar`（已含全部传递依赖；slf4j 后端按惯例仍由你的应用自选）。单 jar 即可编译运行：
> ```bash
> java -cp javathunder-core-0.6.0-with-dependencies.jar QuickStart.java
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
    <version>0.6.0</version>
</dependency>
```

```java
import io.github.oatelauser.thunder.api.*;
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
                try (TorrentClient client = TorrentClient.create()) {  // ④ 下载方（你的业务代码）
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
import java.nio.file.Path;

public class QuickStart {
    public static void main(String[] args) throws Exception {
        try (TorrentClient client = TorrentClient.create()) {
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

**同款冒烟的测试形态**（种子路径可注入，本机无种子自动跳过）：

```bash
mvn -pl javathunder-core -am test -Dtest=QuickStartTest \
    -Djavathunder.quickstart.torrent=/path/to/ubuntu.torrent   # 可选：-Djavathunder.quickstart.targetDir=/path/to/data
```

CI 侧对应 **Actions → CI → Run workflow** 的 `Public quickstart (manual)` 腿（手动触发，种子 URL 可输入覆盖，缺省 Ubuntu 24.04.4）。

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

机制（自动完成，无需干预）：连 Peer → 协商扩展协议（BEP 10）→ 从 Peer 拉取种子元数据（BEP 9）→ **哈希与磁力比对（btih 比 SHA-1、btmh 比截断 SHA-256），不符换源** → 转入正常下载（v2 磁力在此之后多一段层带获取，见 §4.8）。元数据阶段 `state()==QUEUED`、进度 0，属正常。

元数据获取的容错（同样自动）：tracker announce 按周期重试——单轮全部失败时指数退避（间隔 ×2 逐轮放大、任一 tracker 成功即复位），60 秒总窗口内持续补充 Peer；注入了 `peerDiscovery`（§4.2.3）时 DHT 与 tracker 同时供源、互为备份。窗口内仍拿不到元数据则任务转 FAILED，异常信息含已连接/待试 Peer 数便于定位。

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
    <version>0.6.0</version>
</dependency>
```

```java
import io.github.oatelauser.thunder.dht.DhtPeerDiscovery;

try (TorrentClient client = TorrentClient.builder()
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
TorrentClient.builder()
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

### 4.7 HTTP 兜底源（WebSeed，BEP 19）

**依赖**：仅 core。种子里带 `url-list`（指向完整文件的 HTTP 地址）即**自动启用，零配置零代码**——引擎在 Peer 通道之外开一条并行的 HTTP 通道，按件（Range 请求）从 HTTP 源拉取，与 Peer 下载互不重复、互为备份。典型场景：

- **冷启动保险**：新分发的种子还没人做完种（Swarm 里没有 Seed），HTTP 源保证第一批下载者也能跑满
- **镜像分发兜底**：内网大规模分发时即使所有 Peer 都被拖慢，HTTP 文件服务器/对象存储仍能补齐缺口
- **无 tracker 分发**：`url-list`-only 的种子（无 announce）也能直接下载

行为边界（自动处理，无需干预）：

- HTTP 源必须支持 `Range` 请求（绝大多数静态服务器/对象存储都支持）；返回 200 全量的源会被弃用
- 源数据与种子不符（校验失败）连续 2 件即停用 HTTP 通道，Peer 通道照常完成下载；反之 Peer 全挂时 HTTP 通道兜底
- HTTP 下载与 Peer 下载**共享同一对限速桶**（全局 + 任务级，§4.5），不会绕过限速
- 磁力链接的元数据来自 BEP 9（裸 info 字典），天然不含 `url-list`——WebSeed 只对 .torrent 生效
- 当前版本：单文件种子的 `url-list`（BEP 19 本体）；多文件 HTTP 源（BEP 53，草案态）未实现

用 testkit 造一个带兜底源的种子（给自己的集成测试/内网分发用）：

```java
// announceUrl 传 null 即"纯 WebSeed 种子"（无 tracker）
GeneratedTorrent seed = TorrentGenerator.generate(dir, "model.bin", sizeBytes,
        pieceLength, announceUrl, List.of("http://mirror.lan/model.bin"), new Random());
DownloadTask task = client.download(seed.torrentFile(), options);   // HTTP 通道自动生效
```

分发侧只要把完整文件放到任意支持 Range 的 HTTP 服务上（nginx / 对象存储签名 URL 均可），把该 URL 写进种子的 `url-list`。

### 4.8 v2 / 混合种子（BEP 52）

**依赖**：仅 core。**零配置零代码**——引擎自动识别种子的 `meta-version=2` / `file tree` / `piece layers` 并走 SHA-256 Merkle 校验路径。混合种子（同一种子内 v1 SHA-1 与 v2 SHA-256 并存）自动走 v1 面校验（兼容性最广），v2 哈希保留用于未来的 v2 Swarm 接入。

支持范围：

| 形态 | 解析 | 下载 | 校验 | 说明 |
|---|---|---|---|---|
| v1（传统） | ✓ | ✓ | SHA-1 逐件 | 既有行为零变化 |
| v2-only | ✓ | ✓ | SHA-256 Merkle | file tree + piece layers |
| hybrid（混合） | ✓ | ✓ | v1 面（SHA-1）优先 | 双 info-hash 并存，v2 副哈希保留 |
| v2 磁力（btmh） | ✓ | ✓ | SHA-256 Merkle | 两段式元数据（见下）；定位用截断哈希 |

**v2 磁力怎么闭上环（自动，无需配置）**：磁力（`urn:btmh:1220<64hex>`）的元数据
获取分两段——第一段 BEP 9 只能带回 info 字典（含 file tree 与每文件 pieces root，
但 `piece layers` 是 .torrent 顶层字段、不在 info 内）；第二段引擎用 BEP 52 的
hash request/hashes 线消息向 Peer 按 512 对齐块拉取层带，**每块携带到 pieces root
的 Merkle 证明、验证通过才装配**，整带收齐后再折叠终检一次。层带齐备后进入与
.torrent 完全相同的 v2 下载路径。做种侧同时实现了对等方向：收到 hash request 时
从自己的层带供出哈希与证明（块层请求拒绝——本引擎按整件校验、不存块哈希）。

```java
// v2 磁力与 v1 磁力同一入口，零差异：
MagnetUri magnet = MagnetUri.parse(
    "magnet:?xt=urn:btmh:1220<64位hex>&tr=http://tk/announce");
DownloadTask task = client.download(magnet,
    DownloadOptions.defaults().targetDir(Path.of("out")));
```

**为什么 v2 重要**：SHA-1 已被碰撞攻破（2017），主流客户端 2020 年起默认产出 v2/混合种子——不做 v2，能下载的内容面只会越来越窄。

行为边界（自动处理，无需干预）：

- v2 的 piece length 必须 2 的幂 ≥ 16KiB（解析期拒绝违反者）
- 实文件按 piece 边界对齐（BEP 47 填充文件占位但不落盘）
- 逐件 Merkle 校验（16KiB 块 → SHA-256 叶子 → 折叠到层带条目比对）
- 损坏件被拒绝、重下、源拉黑（与 v1 行为一致）；篡改层带在解析期即拒绝

### 4.9 选择性下载（多文件种子只取部分文件）

**依赖**：仅 core。给 `DownloadOptions` 挂一个 `FileFilter` 谓词即可——返回 true 的文件参与下载与完成判定，其余文件不请求、不校验；进度百分比、ETA、tracker 上报的剩余量与 `DownloadResult.bytes()` 全部按"必需字节"换算。磁力路径同样适用（过滤器在元数据就绪后按文件路径求值）。

```java
// 只要 readme 和封面图（路径为种子内相对路径，不含根目录名）：
DownloadOptions options = DownloadOptions.defaults()
    .targetDir(Path.of("out"))
    .fileFilter(FileFilter.paths("readme.txt", "img/cover.png"));

// 或按扩展名（大小写不敏感）：
DownloadOptions isoOnly = DownloadOptions.defaults()
    .fileFilter(FileFilter.extensions("iso", "zip"));

// 或任意谓词（函数式接口，直接 lambda）：
DownloadOptions smallDocs = DownloadOptions.defaults()
    .fileFilter(path -> path.size() == 1 && path.get(0).endsWith(".txt"));
```

行为边界（自动处理，无需干预）：

- **跨界件整件下载**：v1 多文件的 Piece 覆盖拼接流，一件可能同时压住想要与不想要的
  文件——这类件仍整件下载（含少量多余字节），与 qBittorrent 等主流客户端一致；
  完全落在不想要文件上的件绝不请求
- **被过滤文件以稀疏占位物化**：目录形状保持完整（跨界件携带的字节会写入对应文件）
- **完成即全部必需件校验通过**：resume 里残留的非必需件位不干扰完成判定；换过滤器
  重启同一任务安全（缺失的必需件自动补下）
- 过滤器排除一切文件会在任务启动期即失败（ IllegalArgumentException，早失败早改）
- 纯做种（`client.seed(...)`）忽略过滤器——做种必须完整持有
- `seedAfterComplete(true)` + 过滤器：完成后按实际持有的部分集合继续供种

### 4.10 顺序下载（流式消费：边下边看）

**依赖**：仅 core。`DownloadOptions.downloadOrder(DownloadOrder.SEQUENTIAL)`——选件按 Piece 索引从低到高（默认 `RAREST_FIRST` 稀缺优先），首文件最先凑齐。与 §4.9 选择性下载组合即"只下前两集、按顺序下、下完第一集就能看"：

```java
DownloadOptions options = DownloadOptions.defaults()
    .targetDir(Path.of("out"))
    .fileFilter(FileFilter.paths("ep01.mp4", "ep02.mp4"))
    .downloadOrder(DownloadOrder.SEQUENTIAL);
```

行为边界（自动处理，无需干预）：

- 组装中的在途件天然最优先（先收尾手头件再开下一件）；WebSeed 通道与 Peer 通道同序
- 对端没有下一件时自动跳到其后它有的件（不空等）；无人持有的件由后续 Have 到达补选
- 完成判定、进度语义与稀缺优先完全相同（区别只在选件顺序）；两模式可随重启任意切换
- 代价：放弃稀缺优先的 swarm 健康性（人人都顺序下载时稀有件更晚扩散）——只在确需
  按序消费时开启

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

**5.1.2 我要做"原始种子源"**（内网分发的第一台机器）：先用 5.2 造种子 → 数据已在源机器上就直接 **5.1.3 的 `seed()`**（免下载）；数据在别处则下载一次（`seedAfterComplete`）→ 保持进程在线，它就是 Seed。之后每台目标机器下载完成也会自动成为种子源（§1.5 的供给增长）。

**5.1.3 从本地已有文件直接做种**（G2 新增，镜像源机器免下载）：

```java
// 数据已在 dataDir（单文件找 dataDir/<name>；多文件找 dataDir/<name>/目录树）
DownloadTask task = client.seed(
    Path.of("model-x.torrent"),
    SeedOptions.defaults()
        .dataDir(Path.of("/data"))
        .uploadLimitBytesPerSecond(10 * 1024 * 1024));  // 可选：↑10MB/s

// 行为：VERIFYING（对已有数据全量 SHA-1 校验）→ 全过即 SEEDING，开始供种
//      任一件校验失败 → FAILED（提示改用 download() 让引擎只补缺件）
//      future() 不会完成（做种持续）；停止用 cancel(false) 或 client.close()
```

适合内网分发第一台机器：造种子（§5.2）→ 对已有数据 `seed()` → 在线即是种子源，**省掉"先完整下载一次"**。

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

**依赖**：tracker/内嵌 tracker → 仅 core（自建 tracker 用 `javathunder-tracker` 模块，`java -jar` 直跑或内嵌 `EmbeddedTracker`）；去 tracker → + dht。

回顾 §1.3：发现面只交换地址。三条渠道可混用，引擎自动叠加：

| 渠道 | 适用 | 你要做的 |
|---|---|---|
| **HTTP Tracker**（种子里写 announce） | 公网种子默认；内网自建用本项目 `javathunder-tracker`（`java -jar` 即起，见 ROADMAP/README）或 opentracker；测试/进程内嵌用 tools 传递的 `EmbeddedTracker` | 种子生成时填 announce 地址 |
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
 │ 造种子(5.2) + seed   │   ② 发现面 │ download +           │ ◀─────────────────┘
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

### 6.3 传输引擎：默认 NIO 与阻塞参照（什么时候需要关心）

**依赖**：仅 core。默认使用 **NIO 事件循环**（ADR-0003 生产路径，回环实测 110MB/s vs 阻塞 35MB/s）；
**阻塞传输**是保留的可执行规格（参照实现），供差分对拍与调试：

```java
// 方式一：系统属性（不改代码）——切到阻塞参照臂
//   java -Djavathunder.transport=blocking YourApp
// 方式二：显式（api Builder）
TorrentClient.builder()
    .transport(TorrentClient.Transport.BLOCKING)
    .build();
```

不确定就用默认（NIO）；排查传输层问题时切 BLOCKING 对照。

**版本注记（0.3 → 0.4）**：默认传输由 BLOCKING 翻转为 NIO（对齐 ADR-0003 的生产路径
定位）。对外行为不变（协议、API、限速语义都相同），差异是连接 I/O 的承载形态
（每 Peer 一线程 → 单事件循环，回环吞吐约 3×）；保持旧行为显式指定
`.transport(TorrentClient.Transport.BLOCKING)` 或全局 `-Djavathunder.transport=blocking`。

### 6.4 回调跑在你自己的线程上（如 UI 线程）

**依赖**：仅 core。

```java
TorrentClient.builder()
    .listenerExecutor(java.awt.EventQueue::invokeLater)   // Swing 示例；也可换队列/exec
    .build();
```

### 6.5 给自己的项目写集成测试

**依赖**：core（test）+ javathunder-tools（test）。完整可抄模板见 tools 模块的
`LoopbackAcceptanceTest`；三件套 = EmbeddedTracker（传递依赖 tracker 模块提供）
+ TorrentGenerator + FakeSeeder，
另可用 `-Djavathunder.transport=blocking` 让同一测试切到阻塞参照实现各跑一遍（差分；
默认 NIO 与生产一致）。

### 6.6 框架接入（Spring Boot 为例）

**依赖**：仅 core + 你的框架。完整可运行示例见仓库 [`examples/spring-boot`](../examples/spring-boot)
（独立 Maven 项目，不在主 reactor）：`TorrentClient` Bean 生命周期、REST+SSE 进度接口、
demo profile 回环冒烟（`curl` 四步：POST → GET → SSE → DELETE）一应俱全。核心姿势四条：

**① client 是重型资源 → 应用级单例 Bean + 关闭回调。** 一个 client 承载监听端口、
全局限速与事件线程，多任务复用；按请求创建/关闭是典型误用（端口耗尽 + 做不了种）。

```java
@Configuration(proxyBeanMethods = false)
class ThunderConfiguration {
    @Bean(destroyMethod = "close")            // 应用关闭时调 client.close()（姿势④）
    TorrentClient torrentClient() throws IOException {
        return TorrentClient.create();
    }
}
```

**② 回调线程契约：要自定义就注入专用线程池，别借 Web 容器线程。** TaskListener
回调默认跑在库内单线程（守护线程）；`listenerExecutor(...)` 可换成你管理的池——
注入一个 2 线程的小池即可（回调只做轻转发，重处理另投队列）。两个方向都别做：
把回调引到 Tomcat 工作线程（占住请求处理）或公共 ForkJoinPool（阻塞回调拖垮并行流）。

```java
@Bean(destroyMethod = "shutdown")
ExecutorService thunderListenerExecutor() {   // 专用池，随应用关闭
    return Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "javathunder-listener");
        t.setDaemon(true);
        return t;
    });
}

@Bean(destroyMethod = "close")
TorrentClient torrentClient(
        @Qualifier("thunderListenerExecutor") ExecutorService listenerPool) throws IOException {
    return TorrentClient.builder().listenerExecutor(listenerPool).build();
}
```

**③ 进度暴露：REST 轮询读 `snapshot()`，推送用 SSE 转发 `TaskListener`。**
轮询零成本——`GET /api/downloads/{id}` 直接返回 `task.snapshot()`（fraction/速率/
peers/eta/state 一把抓）。推送——`task.addListener(...)` 把 onProgress（~500ms 一帧）
与 onStateChanged 转发进 `SseEmitter`：连接时先补发一帧当前快照；终态
（COMPLETED/FAILED/CANCELLED）后 `emitter.complete()`；处理 onTimeout 与客户端断开
（send 抛 IOException 即关闭），并用一个开关位让后续回调变空操作（API 无 removeListener）。
回调发生在姿势②的专用池上，转发动作本身线程安全。

**④ 优雅停机 = 应用关闭时调 `close()`。** `client.close()`（AutoCloseable、幂等）
停止全部任务（断点已随写随存，重启同目录续传）并释放端口与线程；挂到容器的关闭
钩子上即可——`destroyMethod = "close"`、`@PreDestroy`、`DisposableBean` 三选一，
Spring 对 AutoCloseable Bean 也会自动推断。Bean 依赖关系自动保证销毁顺序：client
先 close，它引用的线程池后 shutdown。

**其他框架同理**：Quarkus（`@ApplicationScoped` + `@PreDestroy`）与 Micronaut
（`@Singleton` + `@PreDestroy`）没有 Spring 的销毁方法推断，显式挂一个 `@PreDestroy`
调 `close()` 即可；四条姿势里只有注解形式随框架变，单例生命周期、专用回调线程池、
snapshot 轮询 + 事件推送、关闭时收尾这四件事完全相同（SSE 在 JAX-RS 侧对应
`SseEventSink`，语义同 `SseEmitter`）。

---

## 第 7 章 API 速查

**api 模块（稳定契约）**

| 类/接口 | 常用成员 | 一句话 |
|---|---|---|
| `TorrentClient` | `download(Path/MagnetUri, options)` `seed(torrent, SeedOptions)` `close()` | 门面 |
| `DownloadTask` | `future()` `state()` `snapshot()` `pause()` `resume()` `cancel(boolean)` `addListener(...)` | 任务句柄 |
| `DownloadOptions` | `defaults()` `targetDir()` `rateLimits()` `restartVerify()` | 单任务配置 |
| `SeedOptions` | `defaults()` `dataDir()` `uploadLimitBytesPerSecond()` | 纯做种配置（配合 `client.seed()`） |
| `MagnetUri` | `parse(String)` | 磁力解析 |
| `TaskListener` | onStateChanged/onProgress/onPieceComplete/onTrackerAnnounce/onPeerConnected/onPeerDisconnected/onError | 事件 |
| `ProgressSnapshot` | fraction(字节级)/rates/connectedPeers/availability/etaMillis | 快照 |
| `TaskState` | QUEUED→VERIFYING→DOWNLOADING→SEEDING/COMPLETED；PAUSED/FAILED/CANCELLED | 状态机 |
| `RestartVerifyMode` | FULL/SAMPLED/NONE | 重启校验档 |
| `PeerDiscoverySource` | `getPeers(infoHash)` | 去 tracker 发现 SPI |

**Builder（TorrentClient.builder()）**：`listenPort(6881)` `maxConcurrentTasks(3)` `maxPeersPerTask(50)` `download/uploadLimitBytesPerSecond(0)` `listenerExecutor(...)` `peerDiscovery(...)` `transport(Transport)`——NIO=事件循环生产路径（缺省），BLOCKING=阻塞参照实现（差分/调试）

**dht 模块**：`DhtPeerDiscovery.create()` / `create(List<String> 内网自举)`

**tracker 模块**：`EmbeddedTracker.start()/start(port)/start(port, interval)`（内嵌，回环）
`EmbeddedTracker.start(InetAddress, port, interval)`（生产形态，通配地址 0.0.0.0 +
默认 6881/1800s）`stats()`（每
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

入口一律走 api：`TorrentClient.create()` / `builder()`（ServiceLoader 自动发现 core 实现，
无需 import 实现类）。

线程模型：NIO 时一个 selector 平台线程管全部连接 I/O；磁盘与哈希在虚拟线程池；事件回调在独立事件线程。深入读 [DESIGN.md](DESIGN.md)（需求与详设）与 [ADR](adr/)（三份关键决策记录）。

## 第 9 章 协议兼容（BEP）速查

实现：BEP 3(v1+多文件)/5(查询模式，可选模块)/6(快速扩展)/9/10/11/12/15/19/20/23/27/52(v2+hybrid)；
BEP 6 范围 = 协商 + HaveAll/HaveNone/Reject（Suggest/AllowedFast 容忍解码，不采纳）。
BEP 52 范围 = v2/hybrid 解析下载 + SHA-256 Merkle 校验（v2 磁力闭环顺延 0.7+）。
未实现：BEP 53(多文件 WebSeed)。互操作实测：ttorrent 双向 + 公网 Ubuntu（[INTEROP.md](INTEROP.md)）。

## 第 10 章 部署要点

- 端口：默认 TCP 6881 入站（放行可显著提升互惠上传——别人连不进你，就只能你连出）；tracker/DHT 为出站
- 磁盘：`<name>.part` + `<name>.jt-resume`；完成自动改名/落位
- 日志：slf4j（记得带后端）；DEBUG 级有 peer/tracker/坏件全量轨迹
- 量级：≤1000 连接、单任务 ≤200 Peer；无 MSE 加密；Windows 做种期 `.part` 名

## 第 11 章 常见问题排错

| 现象 | 原因与处理 |
|---|---|
| 完全没速度、peers=0 | 种子里 tracker 失效且未引 DHT（§4.2.3）；或 6881 未放行只影响上传不影响连出 |
| 磁力任务一直 QUEUED | 没有 tracker 也没注入 peerDiscovery；有 tracker 但短暂全挂时会自动周期重试（指数退避，60s 总超时，§4.2.1），等不到 Peer 持有元数据则 FAILED |
| 没有任何日志 | 缺 slf4j 后端（§2.0 第二个依赖） |
| 速度低于预期 | 先确认带宽/对端；引擎默认已是 NIO（§6.3），排查传输层问题时用 `-Djavathunder.transport=blocking` 切参照实现对照 |
| 重启后从头下载 | `targetDir` 变了，或 `.jt-resume` 被删；续传要求同目录 |
| Windows 做种时文件叫 `.part` | 已知限制（句柄占用），完成/停止后改名 |
| IDEA/JUnit 里运行完全没打印 | 进度 printf 用了 `\r` 不换行——缓冲流不刷新就一行都看不到；行尾改用 `%n`（§2.2 示例已修正） |
| 看似"卡住不动"其实在慢速下载 | 公网种子可能只连到 1 个 Peer、速率 KB/s 级（6GB 需数小时），`join()` 会一直阻塞。先用 §2.1 离线版验证集成，再给 future 加超时观察真实速率 |

---

## 第 12 章 从 0.3 迁移到 0.4

| 变更 | 0.3 用法 | 0.4 迁移 |
|---|---|---|
| 默认传输翻转 NIO | 默认 BLOCKING | 无需动作（对外行为边界不变）；保持旧行为显式 `.transport(TorrentClient.Transport.BLOCKING)` 或 `-Djavathunder.transport=blocking`（§6.3） |
| 客户端入口走 api | 少数代码直接 import `core.internal.client.DefaultTorrentClient` | 改 `TorrentClient.create()` / `builder()`（ServiceLoader 自动发现实现）；编译期不再依赖实现包 |
| `TrackerServer` 移除 | `TrackerServer.start(port, interval)` | `EmbeddedTracker.start(InetAddress.getByAddress(new byte[4]), port, interval)`——生产形态同一实现（通配绑定 + 默认 6881/1800s） |
| `Transports.fromSystemProperty()` 移除 | `.transportFactory(Transports.fromSystemProperty())` | `.transport(Transports.select())`（api 枚举，不再暴露 core 内部类型） |
| `DhtPeerSource` 移除 | `DhtPeerSource.start()` / `bootstrap()` / `knownNodes()` | `DhtPeerDiscovery.create()` / `create(nodes)`（构造即异步自举）；健康度观测用 `DhtPeerDiscovery.knownNodes()` |
| 磁力 announce 对齐 | tracker 全挂时单轮平铺后沉默 | 自动周期重试 + 指数退避（60s 总窗口，§4.2.1），无需改动 |

版本坐标：依赖片段中的 `0.3.0` 全部替换为 `0.4.0`（本文档示例已是 0.4.0）。
