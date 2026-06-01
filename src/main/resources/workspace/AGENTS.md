# ESMind — Elasticsearch Query Compiler (v2)

当前系统为 **v2 Compiler 模式**，不走 AgentScope HarnessAgent。查询通过 REST API 直接执行编译管线。

## 架构概要

```
NL Query → ConfidenceGate(PRE) → SemanticParser(LLM)
         → TemplateEngine.resolve() → ASTBuilder
         → DSLRenderer → EsRestClient → ResultTransformer
```

详见 `docs/current-problems-and-solution.md`。

## 数据环境

- **ES**: 192.168.8.156:9230 (6.5.4)
- **索引**: `history2026_clinical_inhistory_0429122358` (53,461 文档)
- **Schema**: 启动时自动加载全部 53,687 个字段，缓存到本地 JSON
- **业务表**: 25 张核心表通过 `table-semantic.yaml` 配置，其余走 SchemaRegistry 兜底

## 接口

- `POST /api/chat` — 简单查询，返回 answer + decision
- `POST /api/chat/detail` — 详细查询，含 DSL 和 ES 原始结果
- `GET /api/debug/parse?q=xxx` — 调试端点，查看 LLM 输出和 SemanticIR
- `GET /api/health` — 健康检查

## 关键组件

| 组件 | 职责 |
|------|------|
| ConfidenceGate | 无关关键词(REJECTED)、问候语(GREETING)的瞬间拦截 |
| SemanticParser | LLM 实体提取（500 tokens） |
| TemplateEngine | Entity 补全：确定表名、字段、clauseType、context |
| BusinessSemanticRegistry | 25 张核心表的语义配置（YAML） |
| SchemaRegistry | 53,687 个字段的运行时注册表 |
| ASTBuilder | 语义 IR → AST（无状态纯机械转换） |
| DSLRenderer | AST → ES DSL JSON |
