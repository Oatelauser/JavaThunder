# ⚡ JavaThunder

**嵌入你 Java 应用的 BitTorrent 引擎** —— 给它一个 `.torrent` 或磁力链接，它还你一个 SHA-1 校验通过的本地文件；同时你的进程自动成为 P2P 网络节点（边下边传、可做种）。

*An embeddable BitTorrent engine for JVM applications: feed it a torrent or magnet link, get back a verified file — while your process automatically becomes a seeding-capable peer.*

![CI](https://github.com/Oatelauser/JavaThunder/actions/workflows/ci.yml/badge.svg)
![JDK](https://img.shields.io/badge/JDK-21%2B-blue) ![License](https://img.shields.io/badge/license-Apache--2.0-green) ![Version](https://img.shields.io/badge/version-0.3.0-orange) ![Dependencies](https://img.shields.io/badge/runtime%20deps-slf4j--api%20only-success)

## 为什么是它

- **库，不是应用** —— 无 UI、无守护进程；几行代码嵌进你的服务、工具或 Agent
- **零框架税** —— 运行时仅依赖 `slf4j-api`；没有 Netty/Guava，Bencode/协议/存储全部手写
- **轻量与完整是同一套 API** —— 默认配置即轻量（少量连接、下完即停）；注入 DHT、开做种、放开连接数即完整形态，无模式切换
- **P2P 天然可扩展** —— 每个下载节点既是客户端又是服务端：下载到的分片即刻可供他人拉取，**分发的机器越多、总吞吐越大**（对照 C/S：百台机器拉镜像不会挤垮源机）
- **可验证的工程质量** —— 与 ttorrent 双向互操作 + 公网 Ubuntu ISO 实测；双传输差分验收；127 个测试；japicmp 守护 API 兼容性

## 30 秒上手

```java
import io.github.oatelauser.thunder.api.*;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;

try (TorrentClient client = DefaultTorrentClient.builder().build()) {
    DownloadTask task = client.download(
        Path.of("ubuntu.torrent"),
        DownloadOptions.defaults()
            .targetDir(Path.of("downloads"))
            .rateLimits(2 * 1024 * 1024, 512 * 1024)    // ↓2MB/s ↑512KB/s（0=不限）
            .restartVerify(RestartVerifyMode.SAMPLED));  // 大镜像断点恢复：抽样校验

    task.addListener(new TaskListener() {
        @Override public void onProgress(ProgressSnapshot p) {
            System.out.printf("%.1f%%  ↓%dKB/s  peers=%d  eta=%ss%n",
                p.fraction() * 100, p.downloadRateBps() / 1024,
                p.connectedPeers(), p.etaMillis() == null ? "-" : p.etaMillis() / 1000);
        }
    });

    DownloadResult result = task.future().join();  // 返回即所有分片 SHA-1 校验通过
}
```

命令行体验（示例模块）：`java -jar javathunder-cli.jar download ubuntu.torrent --dir downloads`

更多场景（磁力链接的生成与使用、做种、内网大模型镜像分发拓扑、断点续传、监控集成、集成测试模板……）见 **[集成手册](docs/MANUAL.md)**。

## 模块

| 模块 | 作用 | 什么时候需要 |
|---|---|---|
| `javathunder-api` | 纯接口与值类型（稳定契约） | 总是（随 core 传递引入） |
| `javathunder-core` | 引擎实现 | 总是 |
| `javathunder-dht` | BEP 5 DHT（去 tracker 节点发现） | 磁力无 tracker / 去 tracker 分发时 |
| `javathunder-tracker` | 生产级 HTTP tracker：`TrackerServer`（固定端口/Peer 过期清理/统计）、内嵌 `EmbeddedTracker`、可执行 jar | 内网分发自建 tracker 时 |
| `javathunder-tools` | 种子生成器、测试对端（原 testkit，Java 包名不变） | 造种子 / 写集成测试时 |
| `javathunder-cli` | 可执行示例 | 参考/体验 |

`examples/` 下是**独立构建**的接入示例（不在主 Maven reactor）：[`examples/spring-boot`](examples/spring-boot) 演示 `TorrentClient` Bean 生命周期、REST+SSE 进度接口与优雅停机（手册 [§6.6](docs/MANUAL.md)）。

## 安装

**尚未发布到 Maven Central**（计划中）。当前从源码构建：

```bash
git clone https://github.com/oatelauser/JavaThunder.git
cd JavaThunder && mvn clean install
```

```xml
<dependency>
  <groupId>io.github.oatelauser</groupId>
  <artifactId>javathunder-core</artifactId>
  <version>0.3.0</version>
</dependency>
<!-- 运行时请自带 slf4j 后端（如 slf4j-simple / logback），否则日志静默 -->
```

## 特性一览

- **协议**：BEP 3（v1 单/多文件）、BEP 9+10（磁力链接，元数据 SHA-1 自校验）、BEP 11（PEX）、BEP 12（多 tracker）、BEP 15（UDP tracker，`udp://` 自动分派）、BEP 23/20/27；BEP 5 DHT 为可选模块；对未知/BEP 6 消息容忍解码不断连
- **引擎**：rarest-first 调度、tit-for-tat choking + 乐观槽、endgame、逐件 SHA-1、`.part` 预分配 + gather 直写、断点续传（重启校验 FULL/SAMPLED/NONE 三档）、两级双向令牌桶限速
- **安全**：多文件路径穿越防护；磁力元数据哈希强校验；帧/长度/深度多级解析防护
- **传输**：阻塞（默认，简单稳健）与 NIO 事件循环（`-Djavathunder.transport=nio`）双实现，同一套验收差分回归
- **观测**：进度/速率(EMA)/ETA/健康度快照 + 7 类事件回调（独立事件线程，可注入自定义 Executor）

## 性能（回环实测，JDK 21 / Windows）

| 指标 | 数值 |
|---|---|
| 单连接吞吐（NIO） | 110 MB/s（≈880Mbps，真实网络先撞带宽上限） |
| 多连接聚合 | ~100–180 MB/s（上限归因与下一层杠杆见 [PERFORMANCE.md](docs/PERFORMANCE.md)） |
| 限速精度 | 目标 512KB/s → 实测 537KB/s |

## 协议兼容性

已验证与 ttorrent 双向互通（下载与做种）、公网真实种子下载（Ubuntu ISO，[INTEROP.md](docs/INTEROP.md)）。
BEP 52（v2 种子）未实现；MSE/PE 加密明确不做（非 BEP 标准）。

## Known Limits

- 设计量级：≤1000 并发连接、单任务 ≤200 Peer（DHT 爬虫级非目标）
- PEX 仅 IPv4；Windows 做种期间文件保持 `.part` 名（句柄占用，完成即改名）
- 0.x 阶段：API 可能演进，但 CI 用 japicmp 守护二进制兼容（0.3.0 vs 0.2.0 = PATCH：api 零变更；新公共 API 在 tracker 模块，自 0.3.0 起发布）

## 文档

[**集成手册**](docs/MANUAL.md)（从这里开始） · [设计文档](docs/DESIGN.md) · [路线图](docs/ROADMAP.md) · [性能](docs/PERFORMANCE.md) · [互操作](docs/INTEROP.md) · [术语表](CONTEXT.md) · [ADR](docs/adr/)

## 参与贡献

问题与 PR 欢迎。提交规范：conventional commits（`feat:` / `fix:` / `docs:` / `perf:` / `build:`）；每个功能一个提交，测试先行；`mvn verify` 全绿是合并前提（CI 会跑 JDK 21/25 矩阵 + 互操作 + 性能探针）。

## License

[Apache-2.0](LICENSE)
