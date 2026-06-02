# 科研取数需求分析 & Complex Query Engine 设计方案

> 日期：2026-06-02  
> 基于 `科研取数.docx` 需求文档 + ES 索引 `history2026_clinical_inhistory_0429122358` 实测

---

## 一、需求概述

### 输入
- `patient_id` + `visit_id`（前端提供）

### 输出分类

| 类别 | 数据源 | 获取策略 | 是否依赖手术时间 |
|------|--------|---------|----------------|
| 体温单 | `hulitizhengyangli` | 异常优先，否则全取 | ✅ 取大于手术时间 |
| 检查报告 | `?` | 近7天，>50条且术后取3天 | ✅ 条件依赖 |
| 术前三天检查报告 | `?` | 小于手术时间全部 | ✅ |
| 检验报告（主表+明细） | `jianyanbaogaofu` + `jianyanbaogaomingxifu` | 近7天全部 | ❌ |
| 术前三天检验报告 | 同上 | 小于手术时间全部 | ✅ |
| 入院记录（章节） | `ruyuanjilu` (object) | 取指定章节 | ❌ |
| 手术记录（章节） | `shoushujilu` (nested) | 取指定章节 | ❌ 但影响其他 |
| 住院出院小结（章节） | `chuyuanxiaojiexin` (nested) | 取指定章节 | ❌ |

---

## 二、当前 ESMind 能力评估

### 能做的（✅）

| 能力 | 当前支持 | 说明 |
|------|---------|------|
| 单 patient_id 查询 | ✅ | `match_phrase` 查询 |
| nested 表数据读取 | ✅ | `nested` query + `inner_hits` |
| object 对象字段读取 | ✅ | `_source` 直接提取 |
| 时间范围过滤 | ✅ | `range` query + 相对/绝对时间 |
| aggregation 分桶统计 | ✅ | `date_histogram` / `terms` |

### 不能做的（❌）

| 需求 | 当前 ESMind | 为什么不能 |
|------|-------------|-----------|
| **visit_id 二级过滤** | ❌ | 无 visit_id 的 SemanticIR entity 类型 |
| **条件分支**（if 异常体温 then X else Y） | ❌ | 编译器是单向管线，无运行时条件判断 |
| **跨表数据依赖**（先查手术时间，再用它过滤体温） | ❌ | 当前所有 must 条件在 DSL 层一次性执行 |
| **N 天滑动窗口**（"近7天"、"取3天"） | ❌ | 仅支持固定范围，不支持数量感知 |
| **结构化学节提取**（`chief_complaint.src`） | ❌ | 无 entity 类型对应嵌套 object 子字段 |
| **数据格式化输出**（"2024-04-19 07:30 体温：37.4℃"） | ❌ | ResultTransformer 只输出条数 |
| **多手术记录处理** | ❌ | 无"多条手术记录遍历"逻辑 |
| **多表独立提取**（不要求所有表同时存在） | ❌ | 所有条件 AND 连接，缺一表就 0 命中 |

### 核心差距

当前 ESMind 的设计定位是 **「查询编译器」**——把自然语言编译成 DSL，一次执行出结果。  
而科研取数需要的是 **「数据提取引擎」**——按业务规则编排提取步骤，处理条件分支、数据依赖、后处理格式化。

这两个定位差异巨大，**不是一个 QueryPlanner 能解决的**。需要独立引擎。

---

## 三、Complex Query Engine 设计方案

### 3.1 引擎定位

**Complex Query Engine（复杂查询引擎）** = 占位第二引擎，不取代 Compiler/Workflow Engine。

| 引擎 | 输入 | 适用场景 | 处理方式 |
|------|------|---------|---------|
| **Compiler** (v2) | 自然语言 | 简单/复杂单 DSL 查询 | LLM → IR → DSL → ES |
| **Workflow Engine** | 自然语言 | 患者队列跨表(PIVOT)/趋势对比(TREND) | 多步编排 + 工作空间 |
| **Complex Query Engine** | PID+VID (结构化) | **科研取数**：按固定规则逐表提取 | DSL 模板 + 条件逻辑 + 后处理 |

### 3.2 核心概念：ExtractStep

```
ExtractStep = {
  id: string,              // 步骤标识
  table: string,           // 目标表（如 hulitizhengyangli）
  fields: string[],        // 要提取的字段
  filters: Filter[],       // 过滤条件
  depends_on: string[],    // 依赖的上一步 ID
  strategy: {
    conditional: "if_else" | "all" | "threshold",
    if_exists?: string,     // 条件字段
    threshold?: number,      // 阈值（如 37.3）
    sort?: { field, order },
    limit?: { type: "count"|"days", value: number },
    fallback?: "all_if_empty"
  },
  output: { format, fields }
}
```

### 3.3 全量提取定义（对照需求）

```
define PID="002680203800" VID="1"

Step 1: [手术时间] 从 shoushujilu 获取 operation_time
  table: shoushujilu
  fields: [operation_name, operation_time, type_of_anesthesia, postoperative_diagnosis]
  strategy: table, list  ← 多条手术记录全部返回

Step 2: [异常体温] 查 hulitizhengyangli 中 > 37.3°C 且时间 > 手术时间
  table: hulitizhengyangli
  fields: [record_time, vital_sign_value, vital_sign_unit]
  depends_on: [Step 1]
  filters: [
    { field: "hulitizhengyangli.vital_type_name", op: "=", value: "体温" },
    { field: "hulitizhengyangli.record_time", op: ">", value: "@Step1.operation_time" }
  ]
  strategy: {
    conditional: "if_else",
    if_exists: "环节二的结果不为空",
    then: "只取异常体温",
    else: "取全部体温（时间 > 手术时间）"
  }

Step 3: [入院记录章节] 从 ruyuanjilu (object) 提取指定章节
  table: ruyuanjilu
  fields: [
    chief_complaint.src,
    history_of_present_illness.src,
    history_of_past_illness.src,
    social_history.src,
    menses_childbirth.src,
    family_member_diseases_history.src,
    physical_examination.src,
    special_examination.src,
    diagnosis_name_src
  ]
  strategy: simple  ← object 字段，直接从 _source 取

Step 4: [手术记录章节] 从 shoushujilu 提取
  table: shoushujilu
  fields: [operation_name, operation_time, type_of_anesthesia, postoperative_diagnosis]
  strategy: simple（与 Step 1 共享数据，无需重查）

Step 5: [出院小结章节] 从 chuyuanxiaojiexin 提取
  table: chuyuanxiaojiexin
  fields: [first_diagnose_src, treatment.src, discharge_diagnosis_src, src]
  strategy: simple

Step 6: [检验报告近7天] 从 jianyanbaogaofu + jianyanbaogaomingxifu 提取
  table: jianyanbaogaofu (nested)
  strategy: {
    time_range: { type: "relative", value: 7, unit: "day", field: "report_time" },
    include_detail: true  ← 同时查 jianyanbaogaomingxifu
  }

Step 7: [术前三天检验报告]
  table: jianyanbaogaofu
  depends_on: [Step 1]
  filters: [{ field: "report_time", op: "<", value: "@Step1.operation_time" }]
  strategy: all

Step 8: [检查报告近7天]
  table: jianyanbaogao (object)
  strategy: {
    time_range: { type: "relative", value: 7, unit: "day", field: "report_time" },
    condition: {
      if: "count > 50 AND all after surgery_time",
      then: [{ field: "report_time", sort: "asc", limit: "3_days" }],
      else: "all"
    }
  }

Step 9: [术前三天检查报告]
  table: jianyanbaogao
  depends_on: [Step 1]
  filters: [{ field: "report_time", op: "<", value: "@Step1.operation_time" }]
  strategy: all
```

### 3.4 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                    Complex Query Engine                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ExtractDef  (YAML/JSON 定义)                                │
│  ┌──────────────────────────────────────┐                     │
│  │ steps:                               │                     │
│  │   - id: surgery_time                 │                     │
│  │     table: shoushujilu               │                     │
│  │     fields: [operation_time]         │                     │
│  │   - id: temperature                  │                     │
│  │     filters: [vital_type=体温]       │                     │
│  │     depends: [surgery_time]          │                     │
│  └──────────────────────────────────────┘                     │
│                                                                 │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐     │
│  │ Step Executor │ → │ Conditions   │ → │ Aggregator   │     │
│  │ (DSL生成+ES)  │    │ (分支/阈值)  │    │ (合并结果)   │     │
│  └──────────────┘    └──────────────┘    └──────────────┘     │
│         │                                                      │
│         ▼                                                      │
│  ┌──────────────┐                                             │
│  │ Formatter    │ → 结构化 JSON/CSV                           │
│  └──────────────┘                                             │
└─────────────────────────────────────────────────────────────────┘
```

### 3.5 关键设计决策

#### 3.5.1 声明式 vs 程序式

**选择：声明式（DSL + YAML）**，不是写 Java 代码。

```
科研取数 ≠ 编程。需求变化频繁，每次改代码不现实。
```

每一份「科研取数需求」就是一份 YAML 配置：

```yaml
research_name: "术后体温监测数据提取"
version: "1.0"
sources:
  - patient_id: "{{pid}}"
    visit_id: "{{vid}}"

extractors:
  - name: "体温数据"
    type: "nested_table"
    table: "hulitizhengyangli"
    fields:
      - record_time
      - vital_sign_value
      - vital_sign_unit
    filters:
      - field: "vital_type_name"
        op: "="
        value: "体温"
    conditional:
      type: "if_else"
      if:
        exists: { field: "vital_sign_value", threshold: { gt: 37.3 } }
      then:
        filter: { field: "vital_sign_value", op: ">", value: 37.3 }
      else:
        action: "return_all"
    depends_on: ["surgery_time"]
```

#### 3.5.2 Compiler 复用

**不重新造 DSL 生成轮子**。每个 ExtractStep 的内部仍使用 Compiler 的核心组件：

- `EsRestClient` — ES 查询执行
- `DSLRenderer` — 单步 DSL 渲染（只是不走 LLM，走模板）
- `SchemaRegistry` — 字段探测
- `ResultTransformer` — 结果解析（扩展表格->结构化）

Complex Query Engine 只新增：

| 组件 | 职责 |
|------|------|
| `StepExecutor` | 解析 YAML 步骤 → DSL 生成 → ES 执行 |
| `ConditionEngine` | 条件分支判断（if_else / threshold / exists）|
| `DependencyGraph` | 步骤拓扑排序 + 数据传递 |
| `ResultAggregator` | 多步结果合并 → 结构化输出 |
| `TimeArithmetic` | 相对时间计算（近7天、取3天）|

#### 3.5.3 API 入口

```
POST /api/research/extract
{
  "patient_id": "002680203800",
  "visit_id": "1",
  "template": "术后数据提取"       // 对应 YAML 模板名称
}
```

或传内联定义：

```
POST /api/research/extract
{
  "patient_id": "002680203800",
  "visit_id": "1",
  "steps": [ { ... }, { ... } ]   // 内联步骤定义
}
```

---

## 四、与现有架构的关系

```
用户入口
    │
    ├─ 自然语言查询 ─→ ChatController
    │                       │
    │                  PatternDetector
    │                  ┌────┼────┐
    │                  │    │    │
    │            TREND  PIVOT  SIMPLE/COMPLEX
    │                  │    │    │
    │            Workflow      Compiler
    │            Engine        (v2)
    │
    └─ 结构化取数 ─→ ResearchController
                        (新增)
                        │
                  Complex Query
                    Engine
                        │
                  YAML 模板
                定义提取步骤
```

### 路由判断

- `POST /api/research/extract` → Complex Query Engine（根据 template 名称或内联 steps 执行）
- `POST /api/chat` → 现有 PatternDetector + Compiler/WorkflowEngine（不变）

**不存在路由冲突**——科研取数走独立入口，与自然语言查询完全分开。

---

## 五、实现节奏建议

### Phase 1：核心管线可运行

- 实现 `StepExecutor`：单表提取 DSL（非 LLM，模板驱动）
- 实现 `DependencyGraph`：线性拓扑排序
- 实现 `ConditionEngine`：if_else + threshold
- 实现 `TimeArithmetic`：相对时间、滑动窗口
- API: `POST /api/research/extract` 支持内联步骤

### Phase 2：YAML 模板系统

- YAML 解析器 + 验证
- 模板注册/管理
- 模板参数化（`{{pid}}`/`{{vid}}` 占位符）
- 常用模板预置（术后体温、检验报告...）

### Phase 3：高级能力

- 多条手术记录处理（遍历聚合）
- 自定义格式化输出
- 结果缓存（同 PID+VID 短时间内不重复查）
- 审计日志（谁、何时、提取了什么数据）

---

## 六、风险与注意事项

1. **不是 Compiler 的替代** — 科研取数是结构化参数化提取，不是自然语言查询。二者并行不悖。
2. **DSL 层不处理业务逻辑** — 条件分支、时间计算在引擎层，不在 DSL 层。DSL 只负责"查这个表，加这些过滤条件"。
3. **复用优先** — `EsRestClient`、`SchemaRegistry`、`DSLRenderer` 等已有组件尽量少改。
4. **不碰展示层** — 引擎输出结构化 JSON，前端或 Feishu 按需渲染。

---

*本文档分析了 ESMind v2 Compiler 当前能力与科研取数需求的 6 个核心差距，并提出 Complex Query Engine 作为独立引擎的设计方案。核心思路：声明式 YAML 驱动 + 步骤管线 + 条件引擎 + Compiler 组件复用。*
