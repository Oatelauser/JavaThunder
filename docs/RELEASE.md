# 发布标准流程

适用：0.x 阶段的 minor 发布（`0.N.0`）。两次实战校准（0.4.0 / 0.5.0）。
前置：工作区干净、特性全部合入、`mvn verify` 双臂全绿、CHANGELOG 待发布段落内容齐备。

## 1. 版本冻结

| 对象 | 改动 |
|---|---|
| 根 pom + 6 个模块 pom | `<version>`（模块 pom 在 `<parent>` 块内）升为发布版本号 |
| `examples/spring-boot/pom.xml` | 工程版本 + `<javathunder.version>` 属性两处 |
| **japicmp 基线引用** | **一律不动**：三模块的 `oldVersion` file 路径、`oldClassPathDependencies` 字面量版本仍指上一个发布 tag；`newClassPathDependencies` 用 `${project.version}` 自动跟随 |

便捷命令（首处替换，不会误伤基线字面量）：

```bash
sed -i '0,/<version>0\.4\.0<\/version>/s//<version>0.5.0<\/version>/' pom.xml javathunder-*/pom.xml
```

惯例：**开发期 pom 一直携带上一个已发布版本号**，升版只发生在发布提交里（version freeze）。

## 2. 文档同步

- MANUAL：头部版本行、依赖片段 `<version>`、`with-dependencies.jar` 文件名
- README：徽章 version、依赖片段、**测试总数**、特性一览的时效性（顺手清掉与实现不符的旧条目）
- CHANGELOG：待发布段标题补日期（`## 0.5.0（2026-09-16）`）
- ROADMAP / DESIGN：抽查有无与新版本事实不符的陈述

## 3. 双臂验证（发布门槛）

```bash
mvn -B -ntp verify                                  # 默认臂（NIO）
mvn -B -ntp verify -Djavathunder.transport=blocking # 参照臂
```

两臂必须 0 失败；三个 japicmp 判定符合预期（对上一 tag 基线）：
api 期望零变更或纯新增；tracker/tools 纯新增即 MINOR。**有意移除公共 API**
（0.x 政策允许）需同时满足：pom 加 excludes 豁免 + CHANGELOG 列明替代用法 +
下周期基线上移时删除豁免（0.4.0 的 TrackerServer / fromSystemProperty 是先例）。

## 4. 提交 → push → tag

```bash
git commit -m "chore: release 0.N.0 (version freeze, docs sync)"   # 提交体附验证证据
git push origin main
git tag -a v0.N.0 -m "JavaThunder 0.N.0: <英文一段式摘要：特性+兼容性判定>"
git push origin v0.N.0
```

push 后看 CI 首跑（矩阵含最新 JDK 腿；`Public quickstart (manual)` 腿按需手动触发）。

## 5. 发布后收口（下个开发周期开头，约半小时）

基线上移到新 tag——三模块 pom（`oldVersion` file 路径、`oldClassPathDependencies`
字面量、api 的 file 路径）+ **删除已到期的 excludes** + CI 自举命令的 tag 引用
（`ci.yml` 基线安装步骤）+ pom 注释里的"当前 vX.Y.Z"字样；本地基线自举重建：

```bash
git worktree add --detach ../baseline v0.N.0
mvn -B -ntp -f ../baseline -pl javathunder-api,javathunder-tracker,javathunder-tools -am install -DskipTests -DskipJapicmp=true
git worktree remove --force ../baseline
mvn -B -ntp verify   # 三模块 japicmp.diff 应为 "No changes"（工作区 == tag）
```

## 陷阱清单（两次发布实测踩过，勿重蹈）

1. **基线 jar 必须 file-pin，不能用 dependency 坐标**：基线版本与项目版本相同时
   dependency 会被 reactor 解析成模块自身（old==new，护栏静默失效）。0.4.0 周期
   api 踩过，三模块现已统一 file-pin。
2. **基线安装必须从 tag 构建**（worktree），绝不能从工作区 install——否则基线里
   已包含未发布改动，比对失去意义。
3. **本地 `mvn install`（如 examples 依赖链）会覆盖本地仓的基线 jar**：发现护栏
   判定可疑时按第 5 步自举重建；CI 侧不受影响（每次自举安装）。
4. **单模块跑 tools 测试必须带 `-am`**：同版本下本地仓会解析到陈旧 core 构件
   （NoSuchMethodError 假象）。
5. **japicmp 方法级 exclude 必须带括号**：`Class#method()`；无括号按字段模式
   匹配，豁免静默失效（0.5.0 周期实测，门禁翻红抓获）。
6. **网络瞬断时 push 可能部分送达**：以 `git status -sb` 的 ahead 计数与
   `git ls-remote` 为准对账，恢复后原命令重推即可，git 自动合并去重。
