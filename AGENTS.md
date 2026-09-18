# 全局工作约定
## 沟通
- 始终用**简体中文**回复。
- 主动提醒坑点:涉及生产环境、外部系统限制、并发/边界、易错配置时, 明确指出风险和实战注意事项,而不是只给一个"能跑"的方案。
## 工作方式
- 动手前先读相关代码/配置收集上下文;信息不足或有歧义就先确认, 不要基于猜测下结论或做大改动。
- 给结论或代码前,自己先过一遍逻辑、边界和异常路径,发现问题先修正再回复。
## 代码风格
- 遵循 Clean Code:Stepdown Rule / Composed Method / SLAP. 入口方法只做高层表达,细节下沉到意图清晰的私有方法;防卫返回减少嵌套; 复杂逻辑提取方法;命名揭示意图。避免过度设计与过度工程。
- 注释用 Rationale-Oriented 风格:只对复杂业务规则、兼容逻辑、外部约束、执行顺序、易误改处补注释,说明"为什么"及约束/样例;不逐行复述代码。
- 写代码遵循 karpathy-guidelines skill 的约定。

## 工具
- 代码查找优先 serena mcp(JetBrains LSP 可用时)
- 查依赖jar里的符号,也优先用serena mcp,不要反编译(javap 等)

## 语言专项

| 语言    | 遵循的规范                                                        | 强制工具(配进项目)         |
|---------|------------------------------------------------------------------|----------------------------|
| Java    | 阿里 Java 开发手册(以 alibaba/p3c 仓库最新版为准,当前黄山版)   | P3C(Checkstyle/IDEA 插件) |
| JS / TS | 阿里前端规约 f2e-spec / eslint-config-ali                        | F2ELint(封装 ESLint)      |
| Python  | PEP 8 + PEP 257 + PEP 484                       | ruff + black               |
| C / C++ | Google C++ Style Guide(+ C++ Core Guidelines) | clang-format + clang-tidy  |
