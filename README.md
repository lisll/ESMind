# ESMind — 自然语言查询 Elasticsearch

基于 AgentScope Java Harness Framework 构建的 AI Agent，将自然语言问题转换为 Elasticsearch 查询 DSL 并返回结构化结果。

## 架构

```
用户提问（自然语言）
    ↓
┌─ HarnessAgent ─────────────────────────────────┐
│  Workspace: AGENTS.md + knowledge + skills     │
│  + Memory + Session + Subagent orchestration   │
│          ↓                                     │
│  ReAct Loop (Reasoning → Tool Call)            │
│          ↓                                     │
│  EsTool: 查询 ES 的工具层                      │
└────────────────────────────────────────────────┘
    ↓
ES 查询结果 + NL 解释
```

## 版本兼容性

| ES 版本 | 支持状态 | 说明 |
|---------|---------|------|
| 5.x | ⚠️ 基本支持 | REST API 兼容，未充分测试 |
| **6.x** | ✅ **完全支持** | mapping type 包裹、total hits 数字格式已适配 |
| **7.x** | ✅ **完全支持** | 原生 REST API |
| **8.x** | ✅ **完全支持** | REST API 兼容 |

**实现方式**：使用 Elasticsearch Low-Level REST Client（`elasticsearch-rest-client`）直接发送 HTTP 请求，通过 Jackson 解析 JSON 响应。启动时自动检测 ES 版本号并差异化处理：

- **Mapping 结构差异**：6.x 的 mapping 返回 `{"mappings": {"_doc": {"properties": {...}}}}`（type 包裹），7.x+ 返回 `{"mappings": {"properties": {...}}}`（扁平结构）——自动适配
- **Total hits 格式差异**：6.x 返回纯数字 `{"total": 42}`，7.x+ 返回对象 `{"total": {"value": 42, "relation": "eq"}}`——自动识别
- **索引列举**：统一使用 `_cat/indices?format=json`

> 💡 底层不依赖 `RestHighLevelClient`，因此不存在 ES 客户端版本绑定问题。

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.8+
- 可访问的 Elasticsearch 实例（**6.x ~ 8.x**，启动时自动检测版本）

### 配置

```bash
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY 和 ES 连接信息
```

### 运行

```bash
# 交互式模式
mvn compile exec:java \
  -Dexec.mainClass="io.esmind.agent.EsMindAgent"

# 单次查询
mvn compile exec:java \
  -Dexec.mainClass="io.esmind.agent.EsMindAgent" \
  -Dexec.args="上个月销售额最高的产品是什么？"
```

## 工作区结构

```
.agentscope/workspace/
├── AGENTS.md           ← Agent 人格与行为约定
├── MEMORY.md           ← 自动沉淀的长期记忆
├── knowledge/
│   └── KNOWLEDGE.md    ← ES 集群索引映射参考
├── skills/
│   ├── query-writing/   ← 如何构建 ES 查询
│   ├── schema-exploration/  ← 如何发现索引结构
│   └── query-debugging/ ← 如何调试查询问题
└── subagents/
    ├── dsl-writer.md    ← 专职 DSL 生成的子 Agent
    └── result-analyzer.md  ← 专职结果分析的子 Agent
```

## License

Apache 2.0
