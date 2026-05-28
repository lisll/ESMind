# ESMind 架构文档

> 版本: v2.0 (2026-05-28)
> 
> 此次重构将系统从 LLM-only 管线升级为 FastPath/SlowPath 双管线架构，  
> SemanticIR 作为唯一内部查询语言，ASTBuilder 完全无状态化。

---

## 目录

1. [架构演进](#1-架构演进)
2. [管线总览](#2-管线总览)
3. [SemanticIR — 唯一内部查询语言](#3-semanticir--唯一内部查询语言)
4. [Resolution — 实体补全](#4-resolution--实体补全)
5. [ASTBuilder — 纯机械转换](#5-astbuilder--纯机械转换)
6. [DSLCompiler — 无状态化](#6-dslcompiler--无状态化)
7. [FastPath — 高频查询优化](#7-fastpath--高频查询优化)
8. [时间处理](#8-时间处理)
9. [关键决策记录](#9-关键决策记录)

---

## 1. 架构演进

### v0.x — 原始版本（AgentScope Harness）
- 依赖 AgentScope 的 ReAct 循环
- LLM 直接生成 JSON DSL
- 每次查询消耗 50K+ tokens
- 频繁出错（nested path、keyword 后缀）

### v1.0 — v2 Compiler（上轮重构）
- 引入 SemanticIR 中间表示
- LLM 只做实体抽取（500 tokens）
- DSL 由 Java 代码生成
- `TemplateEngine` + `ASTBuilder` + `DSLRenderer` 管线
- 时间约束硬编码在 ASTBuilder 中（TABLE_TIME_FIELDS）

### v2.0 — Compiler Engineering 阶段（本轮重构）
- **删除** `TimeConstraint`：时间统一用 `Entity(type=time)` 表达
- **删除** `StrategySelector`：其逻辑被 `TemplateEngine.resolve()` 替代
- **新增** `Resolution` 阶段：TemplateEngine 从 "AST 节点构建器" 变为 "Entity 补全器"
- **无状态化** ASTBuilder：移除 SchemaRegistry/TemplateEngine/StrategySelector 依赖
- **Entity 自包含**：新增 clauseType/table/field 编译字段
- **提示词更新**：LLM 输出 time 作为 Entity，不再使用 timeConstraint 字段

---

## 2. 管线总览

```
FastPath (QueryDictionary, 待实现)
    │
    ├─→ SemanticIR（完全解析，所有编译字段已填充）
    │
LLM Path (SemanticParser)
    │
    ├─→ SemanticIR（部分解析，仅语义层字段）
    │
    ▼
TemplateEngine.resolve()
    │ 补全 clauseType / table / field / keyword / valueField
    │ 展开 time entity（1个 time entity → N个，每个表一个）
    │
    ▼
SemanticIR（完全解析）
    │
    ▼
ASTBuilder.build()
    │ 按 table 分组
    │ 每组创建 BoolNode + NestedNode
    │ range → filter，其余 → must
    │
    ▼
AST (QueryNode 树)
    │
    ▼
DSLRenderer.render()
    │
    ▼
JSON DSL
    │
    ▼
EsRestClient.execute()
    │
    ▼
ResultTransformer.transform()
```

---

## 3. SemanticIR — 唯一内部查询语言

### 设计原则

1. **字段一旦定义不再增删**：只扩展枚举值，不修改已有字段
2. **自包含**：所有信息都在 Entity 中，ASTBuilder 不需要查外部字典
3. **统一**：FastPath 和 LLM Path 输出完全相同的结构
4. **version 字段**：用于序列化缓存校验

### Entity 字段

| 层级 | 字段 | 类型 | 说明 |
|------|------|------|------|
| 语义层 | `type` | String | disease / lab_item / medicine / surgery / department / patient_id / report_type / exam_item / time |
| 语义层 | `value` | String | 原始值："高血压", "7" |
| 语义层 | `unit` | String | 时间单位："day" / "month" / "year" |
| 语义层 | `operator` | String | gt / gte / lt / lte / eq |
| 语义层 | `numericValue` | String | 数值比较目标 |
| 编译层 | `clauseType` | String | term / match_phrase / exists / range |
| 编译层 | `table` | String | nested 表路径，null=顶级 |
| 编译层 | `field` | String | ES 字段路径 |
| 编译层 | `keyword` | String | keyword 子字段 |
| 编译层 | `valueField` | String | 数值字段路径 |
| 编译层 | `useSynonyms` | boolean | 是否启用同义词 |

### Intent 枚举

| 值 | 含义 |
|----|------|
| `patient_search` | 患者搜索 |
| `patient_count` | 患者计数 |

### version 管理

- `CURRENT_VERSION = 1`（当前）
- 每次 Entity 编译字段变更时 +1
- 枚举值扩展不 bump version

---

## 4. Resolution — 实体补全

### 位置

`TemplateEngine.resolve(SemanticIR ir)`

### 流程

```
输入：[{type:disease, value:高血压}, {type:time, value:7, unit:day}]

1. 非 time entity → resolveEntity():
   - CATEGORY_WORDS 匹配：lab_item/medicine/surgery 的值命中类别词 → exists
   - report_type 路由：按 value → 映射到具体表
   - 模板查找：按 type 查找 templates.json → 填充编译字段
   - 兜底：match_phrase on total_src

2. Time entity → 展开：
   - 收集所有非 time entity 的 table
   - 每个 table 在 TABLE_TIME_FIELDS 中有匹配 → 创建一个 time entity
   - 无匹配 → 顶级 visit_time 兜底

输出：[{type:disease, value:高血压, clauseType:match_phrase, table:shouyezhenduan, field:shouyezhenduan.diagnosis_name}, {type:time, clauseType:range, table:shouyezhenduan, field:shouyezhenduan.diagnosis_time}]
```

### 关键映射表

`CATEGORY_WORDS`：中文类别词 → ES 表名  
`TABLE_TIME_FIELDS`：23 个业务表 → 对应的时间字段  
`templates.json`：entity type → 查询策略（clauseType + table + field）

---

## 5. ASTBuilder — 纯机械转换

### 现状

- **无外部依赖**：不注入 SchemaRegistry / TemplateEngine / StrategySelector
- **无状态**：每次调用 `new ASTBuilder().build(ir)`
- **无硬编码映射**：`TABLE_TIME_FIELDS` 已移到 TemplateEngine
- **纯 switch**：`clauseType` → QueryNode 映射

### 分组逻辑

```
Entity 列表：
  [{clauseType:match_phrase, table:shouyezhenduan, field:shouyezhenduan.diagnosis_name},
   {clauseType:exists, table:shoushujilu},
   {clauseType:range, table:shouyezhenduan, field:shouyezhenduan.diagnosis_time}]

分组结果：
  table=null      → 顶级 BoolNode (must/filter)
  table=shouyezhenduan → NestedNode(path=shouyezhenduan)
    ├─ BoolNode.must: match_phrase(shouyezhenduan.diagnosis_name, 高血压)
    └─ BoolNode.filter: range(shouyezhenduan.diagnosis_time, now-150d/d)
  table=shoushujilu → NestedNode(path=shoushujilu)
    └─ BoolNode.must: exists(shoushujilu)
```

---

## 6. DSLCompiler — 无状态化

### 方案

```java
// DSLCompiler — 纯函数
public class DSLCompiler {
    private final DSLRenderer renderer = new DSLRenderer();

    public String compile(SemanticIR ir) {
        ASTBuilder builder = new ASTBuilder();    // 每次 new
        QueryContainer ast = builder.build(ir);   // 无副作用
        return renderer.render(ast);              // 无状态
    }
}
```

### 与旧版的区别

| 方面 | v1.0 | v2.0 |
|------|------|------|
| ASTBuilder 构造 | 注入 SchemaRegistry + StrategySelector + TemplateEngine | 无参构造 |
| build() 行为 | 调用 TemplateEngine.buildNode() + 查 TABLE_TIME_FIELDS | 纯 Entity→QueryNode 转换 |
| time 处理 | ASTBuilder 决策时间字段 | Resolution 阶段已决策，ASTBuilder 只渲染 |
| 依赖 | 依赖 3 个外部组件 | 零依赖 |

---

## 7. FastPath — 高频查询优化

### 设计（待实现）

```
QueryDictionary (JSON 配置)
  ├── termIndex: {"脑梗" → disease(脑梗死), "WBC" → lab_item(白细胞)}
  ├── timePatterns: ["近(\\d+)天", ...]
  └── templatePatterns: [{"terms":["disease","report_type","time"], ...}]

流程：
  1. QueryDictionary.match(query) → List<Entity>（含编译字段）
  2. 置信度计算
  3. 置信度 ≥ 0.7 → 直接走 ASTBuilder（<100ms）
  4. 置信度 < 0.7 → fallback LLM
```

### 缓存策略

- LRU Cache（Caffeine），5000 条
- TTL 1 小时
- 命中率监控

---

## 8. 时间处理

### 数据流

```
用户说 "近7天"
  │
  ▼
LLM 输出 Entity{type:time, value:"7", unit:"day"}
  │
  ▼
TemplateEngine.resolve() 展开：
  ├─ 收集所有非 time entity 的 table
  ├─ 对每个在 TABLE_TIME_FIELDS 中的 table 创建 time entity
  │   └─ Entity{type:time, clauseType:range, table:jianyanbaogaofu, field:jianyanbaogaofu.report_time}
  └─ 无匹配 → Entity{clauseType:range, field:menzhenjiuzhenjilu.visit_time}
  │
  ▼
ASTBuilder 按 table 分组：
  ├─ time entity 的 table = jianyanbaogaofu → 进入 jianyanbaogaofu 分组
  └─ 在分组内作为 filter 注入 nested 内部
```

### TABLE_TIME_FIELDS

23 个业务表 → 时间字段，存储在 `TemplateEngine` 的静态初始化块中。  
覆盖：门诊就诊、住院、手术、检验报告、处方、病理、护理记录等。

---

## 9. 关键决策记录

| 序号 | 决策 | 理由 | 日期 |
|------|------|------|------|
| 1 | SemanticIR 作为唯一内部查询语言 | 避免多中间层不一致，保证 FastPath/SlowPath 输出统一 | 2026-05-28 |
| 2 | 删除 TimeConstraint，用 Entity(type=time) | 统一数据结构，ASTBuilder 不需要特殊处理 | 2026-05-28 |
| 3 | ASTBuilder 零依赖 | 纯机械转换，无业务决策，方便测试和优化 | 2026-05-28 |
| 4 | TemplateEngine 转型为 Resolver | 保留模板加载能力，但职责从"构建节点"变为"补全实体" | 2026-05-28 |
| 5 | 时间注入由 Resolution 决策，非 ASTBuilder | 符合"ASTBuilder 无业务逻辑"原则 | 2026-05-28 |
| 6 | 禁止 Ontology 化、RuleChain、ClinicalIntent | 过度设计，当前阶段不需要国际编码/复杂推理 | 2026-05-28 |
