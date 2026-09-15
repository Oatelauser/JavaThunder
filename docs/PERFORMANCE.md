# 性能基线与 I/O 模型对比分析

测量环境：Windows 11 / JDK 21.0.10 / 回环网络（RTT≈0，最大化暴露每消息成本）。
探针：`javathunder-testkit` 的 `LoopbackThroughputProbeTest`（`@Tag("perf")`，CI 默认跳过，
手动运行：`mvn -pl javathunder-testkit -am test -Dtest=LoopbackThroughputProbeTest -Dsurefire.excludedGroups=`）。

## 实测（2026-09-14）

| 项 | 吞吐 |
|---|---|
| 纯 SHA-1（128MB） | 1220–1404 MB/s |
| 纯顺序写盘（128MB） | 736–814 MB/s |
| 引擎单 Peer 回环下载（管线深度 8，阻塞传输） | 29 MB/s |
| 引擎单 Peer 回环下载（管线深度 32，阻塞传输） | 35 MB/s |
| 引擎推送化改造后（阻塞传输，C1） | 26 MB/s |
| 引擎单 Peer 回环下载（NIO 事件循环传输，C2） | 48 MB/s |
| C3：Piece 内存组装 + 齐件顺序落盘 + 内联块处理 + 写通道池（NIO） | **111 MB/s（3.2× 阻塞基线）** |
| C4a：去块 clone + 齐件即时指派（含饥饿修复）+ 低水位补发 + NIO 对端（NioSeeder） | **87–91 MB/s** |
| C4a-2：块网格零拷贝组装 + gather 落盘 + 批量请求编码（NIO） | **104–110 MB/s** |
| 引擎 4 Peer 聚合（64MB，管线 32，阻塞传输，C3 前） | 39 MB/s（仅 +11%，见下） |
| C4b：多 Peer 聚合扩展曲线（NIO+NioSeeder，128MB，1/4/8/16 Peer） | 92/100/98/79 MB/s——曲线水平，聚合红线未达（见下） |
| C5-1/2：resume 降频 + 哈希/IO 出 session 监视器 + 调度器分片锁 + 记账无锁（NIO） | 单 Peer 探针 **140 MB/s**；多 Peer 曲线 162/172/184/139 MB/s——全点 +70–90%，曲线仍平（见下） |

### C3 之后的瓶颈（2026-09-15）

111MB/s，距 500MB/s 红线仍差 4.5×。已识别的下一层杠杆（按预期收益排序）：

1. **分配churn**：每 16KiB 块产生 2 次 byte[] 分配（解码 block[] + 记录防御性 clone），
   128MB 下载 = 256MB 垃圾 → 年轻代 GC 压力。解法：解码路径零拷贝化/块缓冲池。
2. **逐块 refill**：每块收尾都进入 session 监视器做一次补发评估；改为低水位触发。
3. **探针对端**：FakeSeeder 逐帧阻塞（每帧 2 次系统调用 + 3 次 memcpy），构成回环测量下界；
   红线判定前应换成 NIO 版对端，否则测的是 seeder 上限。

红线判定（单 Peer ≥500MB/s、8 Peer 聚合 ≥1GB/s）在这些项落地后执行。

### C4a 之后（2026-09-15）

C4a 落地上节三项杠杆（去 clone、低水位 refill、NioSeeder 对端）并新增齐件即时指派
（完成分支在选择器线程直接 refill 下一件，不等哈希/落盘）。首版即时指派引入过饥饿挂死：
齐件后本地位图落定前该件会被重新选中重传，重复块走"不补发"的丢弃路径，issued 清零后
再无唤醒。修复为齐件待校验件从选中器隐藏（verifyingPieces 集合），好件 localSet 后解除。
修复后 NioSeeder 探针 87–91MB/s（即时指派前 81MB/s，约 +10%），距 500MB/s 红线仍差 ~5.5×。

剩余瓶颈（下一层杠杆，按预期收益排序）：
1. **memcpy 链**：每 16KiB 块约 5 次拷贝——seeder 读源拷贝 → 帧编码拷贝 → 内核 →
   readBuffer → 解码 block[] → 组装 arraycopy。解法：解码零拷贝直送组装缓冲（对齐 begin
   的 slice 写）+ 池化块缓冲。
2. **每请求一帧一唤醒**：Request 单帧编码分配 + submit→selector.wakeup 系统调用；改批量
   编码（一次 refill 合成一个 ByteBuffer）可摊薄。
3. **单选择器双向饱和**：90MB/s 时引擎与 seeder 各自的单选择器线程同时承收发；读写分离
   或双选择器可验证是否触顶。

### C4a-2 之后（2026-09-15）

C4a-2 落地上节杠杆 1/2 的引擎侧部分：接收路径块网格零拷贝（`byte[][] blocks` 按槽位挂
引用 + `slotOf` 槽位对齐校验，未请求块直接丢弃），齐件后顺序喂 MessageDigest（取消落盘后
读回）+ `writePieceBuffers` gather 落盘（通道 position 定位，免整件拼接拷贝）；发送路径
`refillRequests` 批量收集后单缓冲一次刷出。探针 2 次取较好：**110MB/s**（另一次 104，波动
±3% 内），较 C4a-1 基线 91MB/s 约 **+21%**。

**双选择器已评估并放弃**：运行期剖析显示引擎与 seeder 的 selector 线程 CPU 占用均 ≈0，
事件循环不是执行瓶颈——读写分离只会增加唤醒链长度，不减少每块工作。C4a 节杠杆 3 关闭。

距 500MB/s 红线仍差 ~4.5×。当前每 16KiB 块成本构成（回环、双方同机）：

1. **解码拷贝**（引擎侧，~1 次）：帧 payload → 新分配 block[] 的那次 memcpy 与随之的
   年轻代分配仍在；零拷贝网格只消掉了后续的组装 arraycopy。
2. **seeder 侧两次拷贝**：readBlock 从源文件读入 + 帧编码再拷一次，每块 2 次 memcpy +
   2 次分配在发送端，回环测速下与引擎成本各占一半，单改引擎无法消除。
3. **唤醒节奏**：管线深度 32 → 每块平均 ~0.5 次 selector 唤醒 + 引擎侧会话监视器进出；
   批量编码已摊薄发送侧唤醒，接收侧仍逐帧。

结论：下一层收益需两侧协同——seeder 帧编码直写池化直接缓冲（FileChannel→socket 的
gather 直传）+ 引擎解码零拷贝（readBuffer slice 直挂网格），以及更深的管线摊薄唤醒。
这些属传输层缓冲池化改造，暂列观察项。

### 多 Peer 扩展曲线与聚合红线判定（C4b，2026-09-15）

`MultiPeerAcceptanceTest` 参数化（`-Djavathunder.transport=nio -DmultiPeer.mb=128
-DmultiPeer.seeders=N`）：NIO 引擎 + N 个对称 NioSeeder + 128MB，计时含连接/握手建立；
N=4/8 各测两次以标定运行方差（±10%）：

| seeder 数 | 聚合吞吐（各次） | 取较好 | 相对单 Peer 加速比 |
|---|---|---|---|
| 1 | 92 | 92 MB/s | 1.00× |
| 4 | 96 / 100 | 100 MB/s | 1.09× |
| 8 | 81 / 98 | 98 MB/s | 1.07× |
| 16 | 79 | 79 MB/s | 0.86× |

**聚合红线判定（ADR-0003：8 Peer ≥ 1GB/s）：未达标。** 实测 8 Peer 聚合 81–98 MB/s，
距 1024 MB/s 差约 **10.4–12.6×**。扩展曲线在运行方差内基本水平，16 Peer 反而回撤——
聚合上限 ≈ 单 Peer 上限（~100 MB/s），加连接不加吞吐。C3 写通道池落地后复测确认：
旧"StorageManager 单句柄串行化"归因**排除**——4 通道分片写仅承载 ~100MB/s 聚合，
而单通道顺序写实测 736MB/s+，存储层余量 7×，从来不是这轮测量的上限。

瓶颈归因（按权重）：

1. **引擎单选择器线程 = 聚合天花板**：NioTransport 全部连接共享一个 selector 平台线程；
   不限速路径下每 16KiB 块的读、帧解码（分配 + memcpy）、零拷贝挂格、issued/inFlight
   记账、低水位 refill 都在该线程内联完成。每块成本与单 Peer 完全相同，故聚合 ≈ 单 Peer；
   多 Peer 只增加该线程的唤醒次数与监视器争用。
2. **全局监视器链**：每块串行穿 3 把全局锁——PieceScheduler（markInFlight/clearInFlight，
   synchronized）、ChokingManager（recordReceived，synchronized）、local 位图
   （pickPieceFor 逐件 localHas，synchronized），全部落在 selector 线程上。
3. **齐件收尾反压事件循环**：finishPiece 在 worker 虚拟线程上持有该 Peer 的 session
   监视器做 SHA-1 + gather 落盘 + **每件一次 resume 文件写**（128MB = 512 次小文件写）
   + Have 广播；selector 线程处理同 Peer 下一块需要同一监视器，事件循环被磁盘/校验
   周期性卡停。16 Peer 时此类窗口与握手建立成本（计时内）叠加，聚合反降至 79MB/s。

下一层杠杆（聚合向 8× 单 Peer 逼近的前提，按预期收益排序）：解码/组装/refill 从
selector 线程卸载到按连接绑定的 worker（事件循环只做 I/O 批收发）；PieceScheduler /
ChokingManager 分片锁或 CAS 化；resume 保存降频（脏标记 + 定时刷）；finishPiece
脱离 session 监视器。

### C5-1/2 之后扩展曲线复测（2026-09-15）

C5-1/2 落地了 C4b 列出的后三项引擎侧杠杆：resume 脏标记降频（progressLoop ≥2s 刷一次，
退出兜底）、finishPiece 的 SHA-1 + gather 落盘移出 session 监视器（状态变更短暂持锁 +
落定前二次竞争检查）、PieceScheduler 分片锁（16 路 stripe + 并发容器热路径）、
ChokingManager 速率记账 ConcurrentMap 化（recompute 仍同步）。复测协议同 C4b
（NIO + NioSeeder + 128MB，每点一次）；单 Peer 探针（LoopbackThroughputProbeTest，
C4a-2 基线 110MB/s）：**140 MB/s**，无回退（+27%）。

| seeder 数 | C4b（2026-09-15 早） | C5-1/2 复测 | 相对本次单 Peer | 相对 C4b 同点 |
|---|---|---|---|---|
| 1 | 92 | **162 MB/s** | 1.00× | +76% |
| 4 | 100 | **172 MB/s** | 1.06× | +72% |
| 8 | 98 | **184 MB/s** | 1.14× | +88% |
| 16 | 79 | **139 MB/s** | 0.86× | +76% |

**形态结论：平曲线未解除。** 绝对值四个点位全部 +70–90%（16 Peer 不再崩塌，已高于
C4b 全部点位），但相对单 Peer 的加速比 4P/8P 仅 1.06×/1.14×，仍 ≤1.5×；16P 相对 8P
回撤 24%。单 Peer 本身从 92 涨到 162（哈希/IO 出监视器同样消除了单 Peer 的齐件停顿），
分母同步抬高，曲线上扬被抵消。512MB 复测 8P 两次：142（jstack 采样扰动下）/206 MB/s，
运行方差 ±10–15%，不影响形态判断。

锁与 GC 均已用直接测量排除为剩余瓶颈（512MB×8 seeder，传输相 20 个 jstack 样本 +
`-Xlog:gc,safepoint`）：

1. **零锁竞争**：传输相全部样本 0 个 BLOCKED 线程——C4b 归因 2/3（全局监视器链、
   齐件收尾持监视器）确认已消除。
2. **GC 无关**：全程仅个位数 Young GC（24M→4M，单次 ≤3.9ms），无并发周期停顿。
3. **seeder 侧请求饥饿**：8 个 NioSeeder 事件循环 CPU ~0–2%，全部停在 WEPoll.wait
   等请求——聚合受限不是对端供不上，而是客户端没发出足够的在途请求。
4. **客户端单选择器回路未饱和**：唯一的事件循环线程 CPU ~40–50%，其余时间在
   WEPoll.wait——既非 CPU 上限也非锁上限，而是**每 Peer 管线节奏封顶**：
   在途窗口 = 32 块 × 16KiB = 512KiB/Peer，实测单 Peer 窗口回转 ~3ms，
   8 Peer 时每窗回转涨到 ~10–22ms（多连接共享一条唤醒/补发回路，16 块一批的
   半水位触发节奏被拉长），聚合 ≈ 窗口总和 ÷ 回转延迟。

下一层杠杆（按预期收益排序）：加深/动态管线深度（每 Peer 在途 512KiB→数 MiB，
回转成本被更多字节摊薄）；跨 Peer 到达驱动补发（任意 Peer 块到达时顺手补齐其他
Peer 的水位缺口）；seeder 响应批量化（现每 Request 一次写，可合成 gather）。
8 Peer 聚合红线（≥1GB/s）需上述管线改造落地后重判。

## 分析

1. **引擎不受组件上限约束**（35 ≪ min(1220, 736)）：瓶颈在引擎自身的逐块节奏。
2. **窗口不敏感**：管线 8→32（窗口 128KiB→512KiB）仅 +20%，说明不是带宽×延迟窗口受限，
   而是每消息固定成本封顶——约 450µs/16KiB 块，来自双方虚拟线程的 park/唤醒链
   （引擎与 seeder 各一次）加每帧系统调用。回环 RTT≈0 把这笔成本放到最大。
3. **真实网络投影**：公网 RTT 20–50ms 时，450µs 的每块开销占比 1–2%，引擎转为带宽受限，
   每消息成本不再是瓶颈；多 Peer 时每连接独立虚拟线程对，聚合并行扩展（50 Peer × 单 Peer 吞吐）。

## 与 Netty 的对比结论（回应"I/O 模型"之问）

- **本量级（≤1k 连接、≤1Gbps 聚合）**：真实网络下两者用户可见吞吐相当——都先撞到
  对端带宽/磁盘/SHA-1，I/O 模型差异被网络 RTT 淹没。
- **Netty 确定赢的场景**：回环/万兆级微基准（事件循环线程不 park，每消息链 ~10–50µs，
  对本探针预计有 3–10× 优势）、聚合多 GB/s、万级连接、池化直接缓冲区的 GC 压力优势。
- **代价**：Netty = 库使用者继承 4MB+ 传递依赖与版本/CVE 管理；本模型 = 代码直线可读、
  每连接可观测（独立线程名）、零额外依赖。

## 优化待办（与 I/O 模型无关，先做这些再谈换模型）

1. **异步 Piece 校验**：验证的 256KB 读回挪出 Peer 线程，与下一 Piece 传输重叠（预计隐藏 ~25% 成本）；
2. ~~**请求批写**~~：已完成（C4a-2：refill 批量收集 + 单缓冲一次刷出）；
3. ~~**流式 SHA-1**~~：读回已取消（C4a-2：齐件后从块网格顺序喂摘要，块乱序由槽位网格解决）；
4. 上述做完重跑探针，若单 Peer 回环仍 <500MB/s 且场景需要，再按 ADR-0001 的重估条件评估 Netty。

重估触发条件（ADR-0001）：单机万级连接（DHT 爬虫类场景）或剖析显示载体线程争用。
