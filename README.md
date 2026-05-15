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

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.8+
- 可访问的 Elasticsearch 实例（7.x+）

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
