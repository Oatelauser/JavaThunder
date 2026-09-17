# BitTorrent v2 / 混合种子（BEP 52）：范围与数据结构决策

---
status: accepted（2026-09-17 批准；实施进入 S2）
依赖: ADR-0002（模块划分，全部改动落 core internal + api 少量新增）
---

SHA-1 已被实际碰撞攻破，主流客户端（qBittorrent/Transmission/libtorrent）2020 年起
默认产出 v2/混合种子，生态持续迁移——本库当前对 v2 种子与 `urn:btmh:` 磁力完全无法
解析。本 ADR 固定 v2 支持（0.6.0）的四个关键决策；实施分四阶段（S2 解析 → S3 校验/
存储 → S4 验收发布），动 TorrentParser/TorrentMetadata/校验管线三层。

## 协议事实（决策依据，已对照 [BEP 52] 与 libtorrent 实现澄清）

1. v2 的 info 字典：`meta-version=2`、`file tree`（每文件 `length` + `pieces root`）、
   `piece length`（必须 16KiB 整数倍）。
2. **Merkle 叶子恒为 16KiB 块（每文件独立成树，不足 2 的幂以零哈希填充）**；
   顶层 `piece layers` 字段（info 字典之外）按文件存储"**一个哈希恰好覆盖一个
   piece**"的那一层（piece=16KiB 时即叶子层；piece=1MiB 时是上方第 6 层）。
   加载时须以层哈希带重算 Merkle 根与 `pieces root` 比对（防篡改，规范要求）。
   小于一个 piece 的文件无 layer 条目（仅 root）。
3. 混合种子：info 字典同时含 v1 `pieces`（+`files`/`length`，**含 BEP 47 填充文件**）
   与 v2 `file tree`，两侧描述同一字节流（填充文件保证实文件按 piece 对齐——v1 视图
   与 v2 视图共享同一条拼接流布局）。**双 info-hash 均对同一份原始 info 字节计算，
   不做任何键剔除**（v1 客户端本就哈希全字典；键剔除会分裂 Swarm）：v1 = SHA-1(raw)，
   v2 = SHA-256(raw)。〔2026-09-17 修正：初稿误写为剔除对方键后计算，经规范与
   libtorrent 行为核实推翻〕磁力可同时携带 `xt=urn:btih:` 与 `xt=urn:btmh:`。
4. v2 磁力：`urn:btmh:1220<64 hex>`（multihash：0x12=sha2-256、0x20=32 字节）；
   DHT get_peers 的 target 用 SHA-256 **截断前 20 字节**（v1 长度兼容）。
   规范未定义磁力路径下 piece layers 的获取（ut_metadata 只传 info 字典，layers
   在其外）——D4 降级预案据此触发：0.6.0 的 v2 磁力止于解析+DHT 定位，
   闭环（v2-only 磁力完整下载）顺延，混合磁力经 v1 哈希路径完成。

## 决策

### D1 范围：三形态一次到位（V1 / HYBRID / V2）

v2-only 与 hybrid 共享约 95% 实现（file tree 解析、Merkle 校验、存储布局）；生态
现实是主流造种工具默认产出 hybrid。**接受三形态**：TorrentMetadata 增加形态枚举；
V1 行为字节级不变（现有 195 个测试是回归门）。拒绝"v2-only 先行、hybrid 缓做"的
切分——双 info-hash 语义贯穿解析/磁力/DHT，事后补的返工面大于一次做对。

### D2 数据结构：piece-layer 哈希带 + 惰性子树根计算器

- 每文件持有一条 **piece-layer 哈希带**（角色等价 v1 的 `pieces` 数组：逐 piece
  比对），随种子加载一次性验证（层带按 padding 规则归并出的根 == `pieces root`，
  不符拒绝种子）。
- 校验为**纯函数子树根计算器**：输入一个 piece 的有序块序列，按 BEP 填充规则算出
  子树根，与层带比对。不缓存中间层、不常驻完整 Merkle 树（内存 ≈ v1 等价物）。
- 存储层零改动：v2 仍是拼接字节流（padding 文件在 file tree 中显式出现，走既有
  多文件路径布局）。断点续传格式与位图语义复用。

### D3 校验粒度：跟随 piece，无自由度（澄清，修正预期）

物理粒度 = piece（piece-layers 只给到该层；16KiB 叶子级单块验证需兄弟路径哈希，
种子内不可得）。**坏件处置与 v1 相同：整件重下**（块网格已收块无法单独验真）。
真实收益在元数据侧：v2 种子可把 piece 定为 16KiB 而种子文件不爆炸——**粒度选择权
交给造种者，本库自适应**。（修正立项沟通中"逐块 16KiB 即时验真"的表述：仅在
piece=16KiB 的种子上成立。）

### D4 磁力 v2：解析与 DHT 进范围；元数据交换留实施核实位

`MagnetUri` 解析 `urn:btmh:`（含 hybrid 双 xt；api 面纯新增，MINOR）；DHT 查找按
截断 20 字节 target。**开放项**：v2 磁力经 ut_metadata 取元数据时 piece layers 的
交换方式（layers 在 info 字典之外，规则需对照 spec 原文核实）列为 S2 首个核实
任务；若实现代价超预期，降级预案为"0.6.0 支持 .torrent 形态的 v2/hybrid + btmh
解析，v2 磁力闭环顺延 0.7"，在 CHANGELOG 明示。

## 内部验收红线（不对外承诺）

- V1/HYBRID 的 v1 面：现有全部测试（含 ttorrent 互操作）零变化通过。
- v2/hybrid 下载：qBittorrent 造种 → 本库下载 → 字节级比对；坏 layer/坏块/篡改
  root 的拒绝路径有直测。
- SHA-256 校验吞吐 ≥ SHA-1 基线（PERFORMANCE.md 探针对照，防止算法替换引入回退）。

## Considered Options

- **只做 hybrid 不做 v2-only**：省 5% 工作量，但 v2-only 是增长形态，拒绝。
- **常驻完整 Merkle 树**（支持未来逐块 proof 扩展）：内存翻倍换无人使用的自由度，
  YAGNI，拒绝（D2 保留升级路径：计算器接口可换缓存实现）。
- **存储层为 v2 引入按文件分树布局**：破坏与 v1/断点续传/WebSeed 的复用，拒绝。

## Consequences

- 双哈希算法长期共存（SHA-1 路径服务于存量 v1 生态，不可删）：校验层出现按形态
  分派的接缝，复杂度永久化。
- TorrentMetadata 构造面扩展（形态/文件树/piece-layers），core internal 可自由
  演进；api 仅 MagnetUri 纯新增。
- DHT 模块需感知截断 target（BEP 5 常规用法不变）。
- 引擎其余部分（调度/choking/限速/传输/WebSeed/断点续传）零感知——v2 是解析与
  校验层的换内核，不是引擎重构。

[BEP 52]: https://www.bittorrent.org/beps/bep_0052.html
