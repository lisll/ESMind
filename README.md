# ESMind — 高性能医疗 Elasticsearch Query Compiler

> 最后更新：2026-06-02

将自然语言医疗查询编译为 Elasticsearch DSL 并返回结构化结果。  
不依赖 LLM 推理生成 DSL，采用 **SemanticIR** 作为唯一内部查询语言，通过 Resolution → ASTBuilder → DSLRenderer 的编译管线执行。

## 架构总览

```
用户输入 (NL)
    │
    ├─ ConfidenceGate ─── REJECTED/GREETING/EXECUTE/CANDIDATES
    │
    ├─ PatternDetector ─── PIVOT / TREND_COMPARE / MULTI_CHAIN ──→ WorkflowEngine
    │                                                                   │
    │                           SemanticParser (LLM Entity Extraction) ←─┘ (回退到 compiler)
    │                               │
    │                          SemanticIR
    │                      (唯一内部查询语言)
    │                               │
    │                    TemplateEngine.resolve (Resolution)
    │                         │ 填充编译字段
    │                         ↓
    │                    ASTBuilder (无状态，纯机械转换)
    │                         │ 按 table 分组 → BoolNode + NestedNode
    │                         ↓
    │                    DSLRenderer → JSON DSL
    │                         ↓
    │                    ES Client → ResultTransformer
    │
    └─ AgentScope ──── MedicalQueryTool (通过 /api/agent/chat 端点)
```

## 核心组件

| 组件 | 路径 | 职责 |
|------|------|------|
| `ConfidenceGate` | `agent/` | 零关键词域检测。LLM 输出 `domain` 字段（medical/greeting/non_medical） |
| `SemanticIR` | `semantic/` | 唯一内部查询语言。Entity 自包含语义+编译两层字段 |
| `SemanticParser` | `semantic/` | LLM 调用，实体抽取。输出 SemanticIR（仅语义层字段） |
| `TemplateEngine` | `template/` | Resolution 阶段：补全 Entity 编译字段。运行时查询 SchemaRegistry/BSR |
| `ASTBuilder` | `ast/` | 纯机械转换，无业务逻辑。按 table 分组构建 NestedNode |
| `DSLRenderer` | `renderer/` | AST → JSON DSL，无状态 |
| `QueryValidator` | `validator/` | DSL 合法性校验 |
| `EsRestClient` | `compiler/` | ES HTTP 通信 |
| `SchemaRegistry` | `compiler/` | ES mapping 加载 + 缓存 (53687 fields) |
| `BusinessSemanticRegistry` | `compiler/` | 25 张业务表的语义配置（YAML），支持运行时注册 |
| `SchemaExplorer` | `compiler/` | 自动表发现，189 个顶层表分类 |
| `PatternDetector` | `workflow/` | 三模式检测：PIVOT / TREND_COMPARE / MULTI_CHAIN |
| `WorkflowEngine` | `workflow/` | 多步工作流执行器（患者队列 pivot、趋势对比、多步条件链） |
| `MedicalQueryTool` | `agent/` | AgentScope @Tool 封装，单一入口替代 300+ 细粒度工具 |
| `ResultTransformer` | `renderer/` | ES 结果 → Markdown 表格 |

## SemanticIR 数据结构

```json
{
  "version": 2,
  "intent": "patient_search",
  "entities": [
    {
      "type": "disease",
      "value": "高血压",
      "clauseType": "match_phrase",
      "table": "shouyezhenduan",
      "field": "shouyezhenduan.diagnosis_name",
      "keyword": "shouyezhenduan.norm_diagnosis_name",
      "useSynonyms": true
    },
    {
      "type": "time",
      "value": "7",
      "unit": "day",
      "clauseType": "range",
      "table": "shouyezhenduan",
      "field": "shouyezhenduan.diagnosis_time"
    }
  ],
  "limit": 20
}
```

## 编译管线

```
NL → ConfidenceGate → PatternDetector → WorkflowEngine 或 SemanticParser → TemplateEngine.resolve → ASTBuilder → DSLRenderer → ES Client
```

## 查询模式

| 模式 | 示例 | 路由 | 耗时 |
|------|------|------|------|
| SIMPLE | `病案首页`、`糖尿病患者` | 直接编译 → ES | ~900ms |
| COMPLEX | `糖尿病且年龄>60岁`、`按月分布` | 直接编译 → ES | ~1000ms |
| PIVOT | `发烧的患者白细胞` | WorkflowEngine (2步) | ~1700ms |
| TREND_COMPARE | `上月vs本月门诊量` | WorkflowEngine (双时间) | ~1800ms |
| MULTI_CHAIN | `糖尿病患者的并且高血压患者` | WorkflowEngine (多步链式) | ~2200ms |
| GREETING | `你好`、`hi` | ConfidenceGate 直接返回 | ~800ms |
| REJECTED | `天气`、`股票` | ConfidenceGate 拒绝 | ~800ms |

## 快速开始

### 运行服务

```bash
# 构建
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
mvn package -DskipTests

# 运行
java -jar target/esmind-agent-1.0.0-SNAPSHOT.jar --server.port=8080
```

### API 接口

```bash
# 查询
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question":"糖尿病患者"}'

# 详细响应（含 DSL）
curl -X POST http://localhost:8080/api/chat/detail \
  -H "Content-Type: application/json" \
  -d '{"question":"门诊就诊记录"}'

# AgentScope Agent 查询
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"question":"糖尿病患者"}'

# 调试：查看 LLM 原始输出和 SemanticIR
curl -s "http://localhost:8080/api/debug/parse?q=%E4%BD%A0%E5%A5%BD"

# 健康检查
curl http://localhost:8080/api/health
```

### 运行测试

```bash
bash scripts/test_suite.sh          # 28 项完整测试
bash scripts/test_suite.sh --detail  # 含详细响应输出
bash scripts/perf_benchmark.sh       # 性能基准
```

## 关键配置

`src/main/resources/application.properties`:
- `esmind.model.api-key` — DeepSeek API Key
- `esmind.es.host` / `esmind.es.port` — ES 地址
- `esmind.es.index` — 目标索引

`src/main/resources/table-semantic.yaml` — 25 张核心业务表语义配置

## 配置变更

| 文件 | 用途 |
|------|------|
| `src/main/resources/application.properties` | 模型/ES/服务端口 |
| `src/main/resources/table-semantic.yaml` | 业务表名、别名、分类 |
| `src/main/resources/templates.json` | Entity 类型→字段映射模板 |

## 索引信息

- 索引: `history2026_clinical_inhistory_0429122358` (ES 6.5.4)
- 文档数: 38,515
- 主要 nested 表: jianyanbaogaofu(检验)、shouyezhenduan(诊断)、menzhenxiyichufang(处方)、shoushujilu(手术)、binglizhenduan(病理)
