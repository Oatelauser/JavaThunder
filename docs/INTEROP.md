# 互操作验收记录（A1）

日期：2026-09-15 ｜ 分支：main ｜ 代码提交：`0357cf2`

## 裁判

**ttorrent-core 1.5**（`com.turn:ttorrent-core`，test 作用域，仅测试类路径）。选它做裁判因为它是一个
2015 年的、与我们零代码共享的独立 BitTorrent 实现——回环测试只能证明我们和自己能对话，ttorrent 证明
我们和别人的实现能对话。

## 双向结果

测试类 `javathunder-tools/src/test/java/.../TtorrentInteropTest.java`（`@Tag("interop")`，
默认 CI 排除）。两种传输各跑两遍，全部通过（每遍两个用例，各约 14s）：

| 方向 | 结果 |
| --- | --- |
| ttorrent 做种 → JavaThunder 下载 → 字节比对 | PASS（阻塞 ×2、NIO ×2） |
| ttorrent 做种 → JavaThunder 下载并 seedAfterComplete → 停 ttorrent → 新 ttorrent 实例从我们的做种客户端下载 → 字节比对 | PASS（阻塞 ×2、NIO ×2） |

复跑命令：

```bash
# 阻塞传输（默认）
mvn -B -ntp -pl javathunder-tools -am test -Dtest=TtorrentInteropTest \
    -Dsurefire.excludedGroups= -Dsurefire.failIfNoSpecifiedTests=false
# NIO 传输
mvn -B -ntp -pl javathunder-tools -am test -Dtest=TtorrentInteropTest \
    -Dsurefire.excludedGroups= -Dsurefire.failIfNoSpecifiedTests=false \
    -Djavathunder.transport=nio
```

全量回归（`mvn -B -ntp package`，interop 默认排除）：200 个测试，0 失败。

## 互操作中发现并修复的协议 bug

以下均为互操作暴露的缺陷，修复不改变引擎设计，理由逐条给出：

1. **做种任务完成即被注销路由**（`DownloadTaskImpl`）。现象：ttorrent 下载方连我方 6881 端口，
   握手即 EOF（"Handshake size read underrrun"）。根因：future 完成回调里立即从
   `DefaultTorrentClient.sessions` 注销任务并释放并发槽，而 `seedAfterComplete=true` 时
   `complete()` 在进入 SEEDING 的**同时**完成 future——做种期入站握手路由随之消失。
   修复：做种任务的注销/释放延迟到真正终态（COMPLETED/CANCELLED/FAILED），用状态监听器触发，
   一次性守卫防重。
2. **同一客户端的第二条链路未被识别**（`DownloadSession.onConnected`）。现象：我方客户端按
   tracker 应答周期性向 ttorrent 监听地址发起出站连接，ttorrent 按 host-id 去重关闭重复链路，
   但其分片簿记已被重复会话扰乱，出现"Downloaded piece#N was not valid"与
   `IllegalStateException: Trying to download a piece while previous download not completed!`。
   根因：会话按 `ip:port` 键控，ttorrent 的入站源端口 ≠ 其监听端口，同一 peer id 被当成两个 peer。
   修复：握手完成后按 **peer id** 去重，重复链路本地直接关闭（BEP 3：peer id 才是身份）。
3. **上传应答乱序**（`DownloadSession.serveUpload`）。现象：ttorrent 收到满长度的 piece 却校验
   失败（字节为零的尾巴，起点随机）。根因：serve 任务提交到无序虚拟线程池，块应答顺序随机；
   ttorrent 1.5 的 `Piece.record` 在收到 `offset=0` 的块时会**重置整片缓冲**（为分片重试设计），
   乱序抵达的 0 号块会静默抹掉先前已记录的块。乱序块协议上合法，但请求序应答是主流实现事实
   标准。修复：每会话一个 FIFO serve 执行器（虚拟线程），跨 peer 并发不变；用裸 socket 探针
   逐块字节校验证明我方上传本身字节正确（3/3 轮 CLEAN），ttorrent 场景修复后全绿。
4. **have-only 对端不可下载**（`DownloadSession.onConnected`）。现象：Ubuntu 官方种子
   （Canonical 做 种 185.125.190.59）握手、unchoke、逐条 have 都正常，但我方一个请求都发不出。
   根因：`PieceScheduler` 只在收到 Bitfield/HaveAll 消息时注册对端，而 BEP 3 的 bitfield 是
   **可选消息**——该做种端只发 have。`peerHave` 对未注册对端静默丢弃，可用性恒为零。
   修复：连接建立即向调度器注册空位图（后续 bitfield/have-all 覆盖，have-none 保持为空）。

另外两处测试侧修正（不改产品代码）：

- `TtorrentInteropTest`：ttorrent 1.5 反编译确认 `share()`/`download()` 均为**非阻塞**
  （`share(int)` 只启动后台线程即返回），leecher 侧改为轮询 `isSeed()`（90s 上限）；
  中途断言 `TaskState.COMPLETED` 修正为 `SEEDING`（`seedAfterComplete=true` 时终态即 SEEDING）。
- 线协议硬化（本就属于本次工作范围）：新增 HaveAll(14)/HaveNone(15)/RejectRequest(16) 编解码，
  未知消息 ID 从断连改为容忍解码为 `UnsupportedMessage` 并由引擎忽略（BEP10 扩展=20、BEP5
  PORT=9、BEP6 Suggest=13 等真实世界常见）；引擎补 HaveAll→全量位图、Reject→释放在途四个 case。

## ttorrent 在 JDK 21 的兼容性

无任何兼容异常（无 SecurityException/反射问题）。补充事实：
- `Client.share()` 与 `Client.download()` 均不阻塞（`download()`＝`share(0)`，内部仅 start 线程），
  等待完成用 `waitForCompletion()` 或轮询 `isSeed()`；
- 其 slf4j 1.6.4 API 与测试类路径上的 slf4j 2.0.17 兼容正常（debug 级别日志可读，是本次定位
  bug #2/#3 的关键）；
- 已知缺陷（对我们无影响）：无效分片后其接收线程会因自身 `IllegalStateException` 死亡且不自愈。

## aria2 与公网冒烟

aria2 装不上（GitHub 被网络策略阻断），改用自带 CLI 做 60 秒部分下载冒烟（2026-09-15）：

- 种子：`https://releases.ubuntu.com/24.04/ubuntu-24.04.4-desktop-amd64.iso.torrent`
  （24.04.5 的 .torrent 已 404，从 24.04 目录取 24.04.4）；
- tracker（https://torrent.ubuntu.com/announce）可达，swarm 1472 seeds / 55 leechers；
- CLI 运行约 70s：`out/ubuntu-24.04.4-desktop-amd64.iso.part`（6.2 GiB 稀疏预分配）与
  `out/ubuntu-24.04.4-desktop-amd64.iso.jt-resume` 均存在；解析 resume（magic JTRESUME v1，
  25390 pieces）：**9 个分片完成，downloaded=2,359,296 字节**（恰为 9 × 256KiB）；
  进度行可见 62–93 KB/s 的下载速率突发。注意：tracker 每次只返回 1 个 peer
  （单播种子 185.125.190.59），速率受此限制。

复跑（PowerShell/Git Bash，jar 路径按本地构建调整）：

```bash
curl -fsSL -o ubuntu.torrent \
  https://releases.ubuntu.com/24.04/ubuntu-24.04.4-desktop-amd64.iso.torrent
java -cp "<cli+core+api+jspecify+slf4j-api 路径>" io.github.oatelauser.thunder.cli.Main \
  download ubuntu.torrent --dir out
# 60s 后中断，检查 out/*.part 与 out/*.jt-resume（位图 1 的个数即已完成分片数）
```

（本次冒烟暴露并修复了上文 bug #4——修复前同一命令 0 字节、无请求发出。）
