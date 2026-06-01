# ESMind Memory

v2 Compiler 模式，当前无运行时状态持久化。跨会话知识通过以下方式维护：

- **`table-semantic.yaml`** — 25 张核心业务表的别名和时间字段配置
- **`data/schema-cache-*.json`** — ES mapping 缓存（自动维护）
- 查询逻辑见 `docs/current-problems-and-solution.md`

## 连接凭据（不提交 Git）

- ES: 192.168.8.156:9230
- DeepSeek API: 通过 application.properties 配置
