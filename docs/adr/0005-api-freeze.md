# ADR-0005：1.0 API 冻结

状态：accepted（2026-09-18，随 1.0.0 发布生效）

## 背景

0.4–0.8 共五个 minor 周期里，api 模块（`io.github.oatelauser.thunder.api`，@NullMarked）
随功能演进累计扩展：`DownloadOptions` 从 6 组件长到 10 组件，`FileFilter` /
`FilePriority` / `DownloadOrder` 三个谓词先后落位，历史兼容构造以 `@Deprecated`
链条保留。ROADMAP 将"1.0 API 冻结走查"定为收官里程碑——1.0 的含义不是功能完结，
而是**对外承诺的开始**：冻结面内的任何改动此后受语义化版本纪律约束。

## 决策

1. **冻结面 = 1.0.0 发布时 javathunder-api 的全部公共类型**（japicmp 对 v1.0.0
   tag 基线守护）：TorrentClient（含 Builder/Transport）、DownloadTask、
   DownloadOptions、DownloadOrder、FileFilter、FilePriority、SeedOptions、
   MagnetUri、ProgressSnapshot、DownloadResult、RestartVerifyMode、TaskListener、
   TaskState、PeerDiscoverySource、TorrentClientProvider、package-info（@NullMarked）。
   `*.internal.*` 子包不在承诺范围（各模块皆然）。
2. **兼容纪律（1.x 期间）**：只做纯新增与实现修复；破坏性变更（移除/改签名/
   语义变化）必须升 major 并走 0.4.0 确立的豁免流程（japicmp excludes +
   CHANGELOG 迁移说明 + 下周期基线上移时删豁免）。
3. **废弃政策**：1.0 面内既有的 4 个 `DownloadOptions` 兼容构造升级为
   `@Deprecated(forRemoval = true, since = "1.0")`（编译期即强警告 + 迁移注释
   给出等价 wither 链）；**2.0 移除**。此后新废弃一律 `forRemoval` 起步并注明
   移除版本。
4. **wither 自洽性**：冻结前补齐 `resumeEnabled(boolean)` 与
   `seedAfterComplete(boolean)` 两个 wither——自此 `defaults()` + wither 链可表达
   规范构造的全部取值，调用方不再有任何理由触碰 10 参构造。
5. **空安全口径**：api 包级 `@NullMarked`；可空成员一律 `@Nullable` 显式标注
   （ProgressSnapshot.etaMillis、MagnetUri.infoHashV2、TaskListener 两回调参数）。
   1.x 期间既有成员的可空性不可收紧也不可放宽（二进制兼容且语义兼容）。
6. **走查修缮（随本 ADR 落地）**：DownloadResult 类注释纠正为按种子形态校验
   （原文"SHA-1"对 v2 不成立）；DownloadTask 全成员、TaskState 全常量、
   TorrentClient.download(Path)、MagnetUri.parse、SeedOptions 工厂/wither 补齐
   Javadoc（语义、异常、边界）。文档属事实纠正与补充，不构成语义变更。

## 后果

- 消费者升级到 1.0 后，可依赖 api 面跨 1.x 补丁/次版本稳定；升级到 2.0 前会得到
  编译期废弃警告而非链接错误。
- 引擎侧（core/dht/tracker/tools）不受冻结约束——internal 包实现自由，行为变化
  经 CHANGELOG 对用户可见。
- 新功能优先以新类型/新组件落位（如未来带宽优先级扩展 `FilePriority` 语义而非
  新接口）；确需破坏时按决策 2 升 major。

## 附注：P3C 构建门禁接入的已知阻断（决策 7，2026-09-18 实测）

AGENTS.md 语言专项要求 Java 侧强制 P3C。实测：官方 p3c-pmd 2.1.1（末版，2020）
的 PMD 6.55 内核在本项目 JDK 21 下类型解析失败（`Unsupported class file major
version 65`）——规则加载后逐文件报错、违规恒为 0，接入 verify 只会得到一个
形同虚设的假门禁，比不接更糟。处置：

1. maven-pmd-plugin 版本与依赖已在根 pom pluginManagement 预置（3.21.2 +
   p3c-pmd 2.1.1 兼容配对），**不挂执行**；上游 alibaba/p3c 出 PMD 7 兼容版后
   一行启用。
2. 过渡期 P3C 以 **IDEA 插件（人工评审口径）+ 本库既有 checkstyle 门禁**执行
   （禁全路径类名/禁星号导入/禁未用导入/方法 ≤50 行，与 Clean Code 约定互补）。
3. 不引入社区 fork（供应链与维护成本不成比例）。
