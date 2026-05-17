# ESMind 架构改造方案

## 一、当前系统问题分析

### 1.1 架构层面
- **过度 Agent 化**：依赖 `HarnessAgent` + 多轮对话，一个简单 NL→DSL 走了完整的 ReAct 链路
- **AgentScope Harness 太重**：workspace、compaction、session 等机制不必要地增加了每次查询的开销
- **LLM 直接生成 DSL**：JSON 拼接交给 LLM，导致 nested path、keyword 后缀频繁出错
- **每次查询都探查 mapping**：`es_get_mapping` 返回 20MB+ mapping，LLM 每次都要重新理解

### 1.2 Token 消耗
- AGENTS.md 过大（含大量 ES 教程、字段映射表、业务知识）
- mapping 上下文反复注入
- 多轮反思/精炼流程
- 单次简单查询可能消耗 50K+ tokens

### 1.3 DSL 稳定性
- nested 路径写错（忘加表名前缀）
- keyword 子字段选错（.accurate vs .raw vs .keyword）
- object 类型误用 nested 包装
- bool/filter 结构不一致

## 二、新架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                     用户输入 (NL)                        │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                  NL Parser (LLM, 轻量)                    │
│    "查询最近7天脑梗死患者"                                 │
│    ↓                                                     │
│    Intent + 结构化条件                                     │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                Schema Linking                            │
│    自然语言字段名 → Schema Registry 字段                  │
│    "诊断" → shouyezhenduan.diagnosis_name.keyword        │
│    "入院时间" → binganshouye.admission_time              │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                DSL Builder (Java 代码)                   │
│    Intent + 条件 → 强约束 DSL 构造                        │
│    • 自动处理 nested path                                │
│    • 自动选择 keyword/text                               │
│    • 自动构建 bool/filter                                │
│    • 自动处理 range/term/match                           │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                Query Validator                           │
│    • 检查 field 是否存在                                  │
│    • 检查 nested path 合法性                              │
│    • 检查 keyword vs text 匹配                            │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                ES Client → 执行查询                       │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                结果格式化 + IR 解释                       │
└─────────────────────────────────────────────────────────┘
```

### 2.2 核心模块

```
io.esmind
├── compiler
│   ├── SchemaRegistry.java        # schema 注册表（启动时从 mapping 加载）
│   ├── SchemaField.java           # 字段元数据
│   ├── SchemaLoader.java          # ES mapping → SchemaRegistry 加载器
│   ├── SchemaLinker.java          # 自然语言 → schema 字段链接
│   └── SynonymDict.java           # 同义词/别名词典
│
├── ir
│   ├── QueryIntent.java           # 查询意图枚举
│   ├── IRQuery.java               # 中间表示（IR）核心数据结构
│   ├── IRFilter.java              # IR 过滤条件
│   ├── IRRange.java               # IR 范围条件
│   ├── IRAggregation.java         # IR 聚合
│   └── IRParser.java              # LLM 输出 → IR 解析
│
├── builder
│   ├── DSLBuilder.java            # DSL 构造器入口
│   ├── NestedQueryBuilder.java    # nested query 构造
│   ├── BoolQueryBuilder.java      # bool/filter 构造
│   ├── RangeQueryBuilder.java     # range query 构造
│   ├── TermQueryBuilder.java      # term/match 构造
│   ├── AggsBuilder.java           # aggregation 构造
│   ├── SortBuilder.java           # sort 构造
│   └── QueryTemplate.java         # 查询模板引擎
│
├── validator
│   ├── QueryValidator.java        # DSL 校验器
│   └── ValidationResult.java      # 校验结果
│
├── linking
│   ├── FieldLinker.java           # 字段链接器
│   └── SynonymLoader.java         # 同义词加载器
│
└── model
    ├── NLQueryRequest.java        # NL 查询请求
    └── QueryResponse.java         # 查询响应（含 IR/DSL/结果）

io.esmind.agent
├── EsTool.java                    # 精简：只保留 ES 通信
└── EsMindCompiler.java            # 编译器入口（替代 EsMindAgent）

io.esmind.web
├── EsMindWebApplication.java      # Spring Boot 入口
└── ChatController.java            # 重构：新链路的 REST 端点
```

### 2.3 Schema Registry 设计

启动时从 ES mapping 加载，写入 JSON 缓存文件。

```json
{
  "schema_version": "1.0",
  "index": "ccm_history_2025_test_clinical_inhistory_0820154042",
  "fields": [
    {
      "field_name": "diagnosis_name",
      "biz_name": ["诊断名称", "诊断", "出院诊断", "首页诊断"],
      "es_path": "shouyezhenduan.diagnosis_name",
      "nested_path": "shouyezhenduan",
      "type": "nested",
      "es_type": "text",
      "keyword_field": "shouyezhenduan.diagnosis_name.accurate",
      "date_field": false,
      "numeric": false,
      "aggregatable": true
    },
    {
      "field_name": "admission_time",
      "biz_name": ["入院时间", "就诊时间", "入院日期"],
      "es_path": "binganshouye.admission_time",
      "nested_path": null,
      "type": "object",
      "es_type": "date",
      "keyword_field": null,
      "date_field": true,
      "numeric": false,
      "aggregatable": true
    },
    {
      "field_name": "patient_id",
      "biz_name": ["患者ID", "患者编号", "病历号"],
      "es_path": "patient.patient_id",
      "nested_path": null,
      "type": "object",
      "es_type": "text",
      "keyword_field": null,
      "date_field": false,
      "numeric": false,
      "aggregatable": false
    }
  ]
}
```

### 2.4 IR 设计

NL → Intent / IR（LLM 输出）→ DSL Builder（代码）

**LLM 输出的 IR 格式：**

```json
{
  "intent": "patient_search",
  "explanation": "识别到：查询对象为患者，条件为脑梗死，时间范围为最近7天",
  "filters": [
    {
      "field": "诊断名称",
      "operator": "contains",
      "value": "脑梗死",
      "logic": "must"
    }
  ],
  "time_range": {
    "field": "入院时间",
    "operator": "last_n_days",
    "value": 7,
    "logic": "must"
  },
  "limit": 20,
  "sort": {
    "field": "入院时间",
    "order": "desc"
  }
}
```

关键：**LLM 只输出字段的业务名称（如"诊断名称"），不接触 ES 字段路径**。由 Schema Linker 将业务名称映射为 ES 字段。

### 2.5 DSL Builder 设计

```java
// DSLBuilder.java - 核心构造器
public class DSLBuilder {
    
    public JsonNode build(IRQuery ir, SchemaRegistry schema) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        
        // 1. 构建查询
        BoolQueryBuilder boolBuilder = new BoolQueryBuilder();
        for (IRFilter filter : ir.getFilters()) {
            SchemaField field = schema.link(filter.getField(), filter.getValue());
            QueryNode clause = buildClause(field, filter);
            boolBuilder.addClause(filter.getLogic(), clause);
        }
        
        // 2. 时间范围
        if (ir.getTimeRange() != null) {
            SchemaField timeField = schema.link(ir.getTimeRange().getField(), null);
            QueryNode rangeClause = RangeQueryBuilder.build(timeField, ir.getTimeRange());
            boolBuilder.addClause("must", rangeClause);
        }
        
        root.set("query", boolBuilder.build());
        root.put("size", ir.getLimit() != null ? ir.getLimit() : 20);
        
        // 3. 排序
        if (ir.getSort() != null) {
            // ...
        }
        
        return root;
    }
    
    private QueryNode buildClause(SchemaField field, IRFilter filter) {
        if (field.isNested()) {
            // 自动封装 nested query
            return NestedQueryBuilder.build(field, filter);
        }
        // 非 nested：直接构建
        if (filter.getOperator().isTerm()) {
            return TermQueryBuilder.build(field, filter);
        }
        return MatchQueryBuilder.build(field, filter);
    }
}
```

### 2.6 Query Template Engine

预定义模板（减少每次推理的开销）：

```java
public enum QueryTemplate {
    // 诊断查询模板
    DIAGNOSIS_SEARCH("诊断查询", Map.of(
        "diagnosis_name", "field:诊断名称",
        "time_range", "field:入院时间, operator:last_n_days, value:30"
    )),
    
    // 时间范围模板
    TIME_RANGE("时间范围查询", Map.of(
        "start_time", "field:入院时间, operator:gte",
        "end_time", "field:出院时间, operator:lte"
    )),
    
    // 聚合模板
    DIAGNOSIS_AGGS("诊断聚合", Map.of(
        "agg_field", "field:诊断名称",
        "size", "value:20"
    ));
}
```

### 2.7 Prompt 优化（缩减到 10%）

**新的 System Prompt：**

```
你是一个医疗 ES 查询编译器。将用户问题转为结构化的查询意图（IR），
不要生成 ES DSL。

约束：
1. 输出 JSON IR 格式，字段名用中文业务名称
2. field 从以下列表中选择：{{schema_fields_preview}}
3. operator 支持：eq, contains, gt, gte, lt, lte, last_n_days, between
4. 不要解释，只输出 JSON
5. 不确定时把字段名原样输出，不要猜测 ES 路径
```

## 三、重构步骤

### Phase 1（当前） — 核心编译器
- [ ] SchemaRegistry + SchemaLoader（从 ES mapping 加载 + JSON 缓存）
- [ ] IR 数据结构（IRQuery, IRFilter, IRRange）
- [ ] DSL Builder 核心（BoolQueryBuilder, NestedQueryBuilder, RangeQueryBuilder, TermQueryBuilder）
- [ ] QueryValidator
- [ ] 精简 EsTool（只保留 ES 通信）
- [ ] 更新 application.properties（添加 schema 缓存路径）

### Phase 2 — LLM 集成
- [ ] IRParser（LLM 输出 → IR 对象）
- [ ] SchemaLinker（业务名称 → ES 字段）
- [ ] SynonymDict
- [ ] 轻量 LLM 调用（非 AgentScope）
- [ ] 新版的 /api/chat 端点

### Phase 3 — 优化 & 完善
- [ ] QueryTemplateEngine
- [ ] 更多模板和同义词
- [ ] Token 统计和优化
- [ ] Web UI 改造
- [ ] 测试和调优

## 四、风险分析

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| LLM 输出的 IR 不稳定 | DSL 生成失败 | Validator 校验 + fallback 策略 |
| Schema 字段覆盖不全 | 有些查询映射不到 | SynonymDict 逐步扩充 |
| 旧索引兼容 | 旧索引结构不同 | SchemaRegistry 支持多索引 |
| 启动时 mapping 加载慢 | 首次启动延迟 | JSON 缓存 + 异步加载 |
