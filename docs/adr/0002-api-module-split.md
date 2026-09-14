# 公共 API 与实现分模块（api / core / testkit / cli）

库定位为第三方依赖，公共 API 的稳定性是一等约束。Maven 多模块切分为
`javathunder-api`（纯接口与值类型，唯一依赖是 JSpecify 注解）、`javathunder-core`（引擎实现，
依赖 api）、`javathunder-testkit`（内嵌 Tracker、种子生成器、假 Peer，供使用者写集成测试）、
`javathunder-cli`（可执行示例）。api 模块在编译期阻断实现类型漏进公共签名——core 若想
在对外方法里暴露内部类型，直接编译失败；约定式隔离（internal 包 + 文档）做不到这一点。
包根为 `io.github.oatelauser.thunder`（groupId `io.github.oatelauser`，Central 免域名校验）。

## Consequences

- bencode 编解码、存储、线协议等先藏在 core 内部包，等真有用户要独立使用再抽模块；
  0.x 阶段抽出不算破坏性变更，反向合并公共模块才是。
- japicmp 等首个 0.x tag 存在后启用。
