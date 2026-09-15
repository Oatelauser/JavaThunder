# JavaThunder

JDK 21+ 的 BitTorrent 下载库：以第三方依赖的形式嵌入你的应用，轻量或完整地获得 P2P 下载能力。
零框架依赖（运行时仅 `slf4j-api`），手写 NIO 事件循环承载 Peer 连接（ADR-0003）。

```java
try (TorrentClient client = TorrentClient.builder()
        .listenPort(6881)
        .maxConcurrentTasks(3)
        .build()) {

    DownloadTask task = client.download(
        Path.of("ubuntu.torrent"),
        DownloadOptions.defaults()
            .targetDir(Path.of("downloads"))
            .rateLimits(2 * 1024 * 1024, 512 * 1024)); // ↓2MB/s ↑512KB/s（0 = 不限）

    task.addListener(new TaskListener() {
        @Override public void onProgress(ProgressSnapshot p) {
            System.out.printf("%.1f%%  ↓%dKB/s  peers=%d  eta=%s%n",
                p.fraction() * 100, p.downloadRateBps() / 1024,
                p.connectedPeers(), p.etaMillis());
        }
    });

    DownloadResult result = task.future().join(); // 每个 Piece 均已通过 SHA-1 校验
    task.pause(); task.resume();
    task.cancel(false); // true = 连同本地数据一起删除
}
```

## 坐标（0.1.0 发布准备中）

```xml
<dependency>
  <groupId>io.github.oatelauser</groupId>
  <artifactId>javathunder-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

`javathunder-api`（纯接口，供编译期引用）与 `javathunder-testkit`（内嵌 Tracker / 种子生成器 /
假种子方，供你的集成测试）同 group 下可用。CLI 示例见 `javathunder-cli`。

## 特性

- **协议**：BEP 3（v1 种子 + 线协议）、BEP 10（扩展握手）+ BEP 9（ut_metadata 磁力链接）、
  BEP 12（多 Tracker 分层）、BEP 20（peer id 规范）、
  BEP 23（紧凑 peer 表）、BEP 27（私有种子标志）；对 BEP 6/10 消息容忍解码不握手即断
- **磁力链接**：`MagnetUri.parse(...)` → `client.download(...)`，info-hash SHA-1 自校验后
  转正常下载；需 tracker 或 DHT（后续）发现持有元数据的 Peer
- **引擎**：Piece 内存零拷贝组装、逐件 SHA-1 校验、`.part` 预分配 + gather 直写、
  断点续传（`.jt-resume`，CRC32 + info-hash 绑定 + 重启重校验）、rarest-first 调度、
  tit-for-tat choking + 乐观槽、endgame 判定、两级（全局 ∧ 任务）令牌桶限速
- **互操作**：与 ttorrent 双向互通（下载与做种，阻塞/NIO 双传输），公网 Ubuntu ISO 实测下载
  （[docs/INTEROP.md](docs/INTEROP.md)）
- **传输**：可切换双实现——NIO 事件循环（默认开发中）与阻塞参照实现
  （差分验收与调试工具，`-Djavathunder.transport=nio|blocking`）
- **观测**：EMA 平滑速率、ETA、availability、tracker/peer/piece/state 全事件回调
  （专用事件线程，回调异常不影响协议）

## 性能（回环实测，Windows / JDK 21）

单连接 110 MB/s（≈880Mbps）；真实网络下先撞带宽上限。
多连接聚合 ~100 MB/s 封顶（引擎单选择器线程内联处理所致，与传输实现无关——
见 [docs/PERFORMANCE.md](docs/PERFORMANCE.md) 的归因与下一层杠杆）。

## Known Limits（设计量级，非目标）

- 并发连接 ≤ 1000、单任务 ≤ 200 Peer 的场景；更大规模（DHT 爬虫级）不在当前设计内
- 单文件种子（多文件是第二阶段）；无磁力链接/DHT/PEX/UDP Tracker（均在路线图）
- 无连接加密（MSE/PE）——非 BEP 标准，明确不实现
- Windows 上做种期间文件保持 `.part` 名（句柄占用），完成即改名

## 文档

- [需求与设计](docs/DESIGN.md) · [路线图/BEP 覆盖](docs/ROADMAP.md) · [性能](docs/PERFORMANCE.md)
- [互操作验收](docs/INTEROP.md) · [术语表](CONTEXT.md) · [ADR](docs/adr/)
- Apache-2.0
