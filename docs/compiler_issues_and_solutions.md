# ESMind v2 Compiler — 查询准确性问题与解决思路

> 编写日期：2026-06-02  
> 基于患者白浩（002680203800）实测定性

---

## 目录

1. [问题一：多表提取误用为多表 AND 过滤](#问题一多表提取误用为多表-and-过滤)
2. [问题二：total_src 空值过滤导致漏数据](#问题二total_src-空值过滤导致漏数据)
3. [问题三：答案层不提取字段值](#问题三答案层不提取字段值)
4. [问题四：查询类型感知缺失](#问题四查询类型感知缺失)
5. [问题五：诊断字段数据质量无感知](#问题五诊断字段数据质量无感知)
6. [问题六：跨表查询无 Plan 分层](#问题六跨表查询无-plan-分层)
7. [问题七：科研取数场景的架构定位（Workflow Engine vs Complex Query Engine）](#问题七科研取数场景的架构定位workflow-engine-vs-complex-query-engine)
8. [附录：框架层面改进建议](#附录框架层面改进建议)

---

## 问题一：多表提取误用为多表 AND 过滤

### 现象

用户问「提取患者 X 的首页、诊断、检验报告、门诊病历」，生成的 DSL：

```json
{
  "bool": {
    "must": [
      { "exists": { "field": "binganshouye.admission_time" } },
      { "exists": { "field": "ruyuanjilu.admission_time" } },
      { "nested": { "path": "chuyuanxiaojiexin", ... } },
      { "exists": { "field": "menzhenjiuzhenjilu.visit_time" } },   // ← 住院患者无此表
      { "nested": { "path": "jianyanbaogaofu", ... } },
      { "match_phrase": { "patient.patient_id": "002680203800" } }
    ]
  }
}
```

**结果**: 0 命中。因为白浩是住院患者，没有 `menzhenjiuzhenjilu`（门诊就诊记录），整条 AND 查询全废。

### 根因

**编译器把"多表提取"当成了"多表同时存在的筛选条件"**。用户语义是"给我这个人的各部分数据，没有的留空"，但编译器实现的是"只返回一个同时拥有所有表的文档"。

关键代码路径：

```
用户问题
  → SemanticParser.parse()           # LLM 识别出 5 个 report_type
  → TemplateEngine.resolve()         # 每个→exists+table
  → ASTBuilder.build()               # 每个→root.addMust() → 全部 AND
  → DSLRenderer.render()             # 生成多表 must DSL
  → ES → 0 hits                      # 没有文档同时拥有所有表
```

第 64–75 行 `ASTBuilder.java`：

```java
// 无 group Entity → 按 table 分组，用 MUST
for (Map.Entry<String, List<SemanticIR.Entity>> entry : groupByTable.entrySet()) {
    QueryNode node = buildTableGroup(ents);
    if (node != null) {
        root.addMust(node);   // ← 每个表都加 MUST
    }
}
```

### 核心矛盾

| 用户意图 | DSL 实现 | 问题 |
|----------|---------|------|
| 提取首页+诊断+检验 | 5 个表全部 `must` AND | 缺任何一表就 0 命中 |
| 按 patient_id 取文档+独立读各表 | 按 patient_id 取文档后分别读 nested | 编译器不会区分"提取"和"筛选" |

### 解决思路

**方案 A：查询类型分层（推荐）**

在现有的 `patient_search`/`patient_count` 基础上，新增 `patient_data_extract` 意图类型：

```
SemanticIR.intent = "patient_data_extract"
```

编译器检测到此意图后：
1. 主查询只按 `patient_id` 匹配，不做多表 exists
2. 各表数据的提取在结果层完成（从 `_source` 按需读出）
3. 本意是"数据组织"问题，不要上升到 DSL 层面

**方案 B：多表 exists 用 should 替代 must**

通用解法：将多表 exists 改为 `should` + `minimum_should_match: 1`，保证至少有一个表匹配即可返回文档。

```
"must":   [ { "match_phrase": { "patient.patient_id": "002680203800" } } ],
"should": [ { exists: binganshouye }, { exists: jianyanbaogaofu }, ... ],
"minimum_should_match": 1
```

问题：如果 5 个表全不存在的罕见文档也会返回。但对于「按 patient_id 查」的场景，这不会发生。

**方案 C：拆为 N 条独立 ES 查询**

编译器将每个表生成独立 DSL，分别查询后合并结果。适用于数据提取场景（不要求跨表关系），但会增加 ES 请求数。

### 对比

| 方案 | 改动范围 | 优点 | 缺点 |
|------|---------|------|------|
| A: 新意图类型 | SemanticIR + ChatController | 最准确，语义清晰 | 需加意图判断逻辑 |
| B: must→should | ASTBuilder（改通用逻辑） | 改动最小 | 可能影响其他查询类型 |
| C: 拆多条 | EsMindCompiler 执行层 | 隔离性好 | N 倍 ES 请求 |

**推荐**: 方案 A（上层意图判断）为主，方案 B（must→should）作为兜底兼容。

---

## 问题二：total_src 空值过滤导致漏数据

### 现象

查询"血常规"时，编译器生成的 DSL：

```json
{
  "nested": {
    "path": "jianyanbaogaofu",
    "query": {
      "bool": {
        "must": [
          { "exists": { "field": "jianyanbaogaofu" } },
          { "match_phrase": { "total_src": "血常规" } }     // ← 此字段为空
        ]
      }
    }
  }
}
```

实际数据：白浩的 82 条检验记录中，`total_src` 全部为空，所以 0 命中。

### 根因

`TemplateEngine.resolveEntity()` 第 344–348 行：

```java
// 4. 兜底：全文搜索
entity.setClauseType("match_phrase");
entity.setField("total_src");
log.warn("No template for type '{}', fallback to total_src match_phrase", type);
```

当 LLM 输出的 entity type 是 `lab_item` 但没有具体项目名（只有类别"血常规"），模板查找没命中，fallback 到 `total_src` 文本匹配。

### 解决思路

1. **运行时 Schema 感知**：ES mapping 中 `total_src` 字段若无数据（或极少数据），fallback 不应使用此字段
2. **SchemaRegistry 对 "empty field" 元信息感知**：扫描 ES 数据，记录每个字段的 value_count，空值率 >80% 的字段不应用于 match 条件的兜底
3. **降级策略**：当唯一过滤条件（除 patient_id 外）结果是空字段时，应丢弃该条件，改为纯 patient_id 查询 + 结果层过滤

---

## 问题三：答案层不提取字段值

### 现象

首页查询 ES 返回了完整数据（sex=男、age_value=24、occupation_name=专业技术人员……），但 answer 只输出：

```
共查询到 **1** 条结果。
| 患者ID |
| ------ |
| 002680203800 |
```

### 根因

`EsMindCompiler.java` 第 181–184 行：

```java
ResultTransformer.TransformResult tr = resultTransformer.transform(esResult, container);
response.setAnswer(tr.getSummary());          // ← 只输出条数
response.setMarkdownTable(tr.getMarkdownTable());  // ← 只输出 patient_id 表格
```

`ResultTransformer` 不识别 `binganshouye` 中的字段，不会把 sex、age_value 等提取到 answer 里。

### 解决思路

这属于**展示层设计**，用户已确认后端先不处理。留作专门设计。但建议：

1. **结构化元信息**：在 `SemanticIR` 或 `QueryResponse` 中附加 "本次查询涉及的表和字段" 信息
2. **结果结构化**：`ResultTransformer` 输出分两层——原始数据（JSON）+可展示数据（已解析的关键字段）
3. **前端/展示层**：根据语义决定如何渲染（表格/卡片/自由文本）

---

## 问题四：查询类型感知缺失

### 现象

当前 `SemanticIR.intent` 只有 `patient_search` 和 `patient_count` 两种。这意味着：

- "提取白浩的首页"和"60岁以上糖尿病患者"走同一套编译逻辑
- 前者是**数据提取**（已知患者→展开数据），后者是**患者筛选**（条件→找患者）
- 两种场景对 DSL 的要求完全不同

### 根因

`SemanticParser` 的 system prompt 只定义了两种 intent，LLM 无法把"提取"和"搜索"区分开。在没有 `data_extract` intent 的情况下，后端走的是通用编译逻辑。

### 解决思路

1. **LLM prompt 增加 intent 类型**：`patient_data_extract`
2. **检测信号**：查询中包含具体 patient_id + 多个候选表名（首页/诊断/检验等）+ 无新增条件 → 判定为 data_extract
3. **Compile 路径分叉**：
   - `patient_search`（搜索）：现有 DSL 逻辑
   - `patient_data_extract`（提取）：纯 ID 查询 → 结果层按需取各表数据

---

## 问题五：诊断字段数据质量无感知

### 现象

白浩的 6 条出院诊断，`diagnosis_name` 字段全部为空。ES 结果中只有 `diagnosis_type_name`（诊断分类名）有值：

```
1. [手术并发症] 手术并发症           ← diagnosis_name 为空
2. [非手术并发症] 非手术并发症
3. [院内感染] 院内感染
4. [病理诊断] 病理诊断
5. [死亡原因] 死亡原因
```

### 根因

这是**源数据质量**问题，不属于编译器的 Bug。但编译器对此无任何感知——它并不知道诊断名没取到，也不会回退到其他字段。

### 解决思路

1. **字段回退机制**：在 `TemplateEngine` 层给诊断 entity 配置多级回退字段
2. **数据质量报告**：SemanticCatalog 层增加字段填充率统计，编译器可在 debug 信息中提示
3. **运行时探测**：首次执行后，检查诊断实体的结果数据，若 `diagnosis_name` 全部为空，尝试用 `norm_diagnosis_name` 或 `diagnosis_type_name` 回退

---

## 问题六：跨表查询无 Plan 分层

### 现象

虽然 `QueryPlanner` 已实现了 `hasCrossNestedAnd()` 检测，但它在**编译管线内从未被使用**。Controller 直接调用了 `compiler.compile()`，跳过了 Planner 的评估结果。

### 根因

`QueryPlanner` 是个空转模块——`evaluate()` 返回 Plan 类型，但没有地方消费它来决定走哪条编译路径。当前所有查询走同一条 `Compiler.compile()` 路径。

### 解决思路

1. **Planner 接入 ChatController 路由**：在调用 `compiler.compile()` 之前，先做 Plan 评估
2. **Plan 类型驱动的执行路径**：
   - `SINGLE` → 现有 `compiler.compile()` 路径
   - `COMPLEX` → Complex Query Engine（多 DSL 分步执行）
   - `TASK` → Workflow Engine（PIVOT/TREND/MULTI_CHAIN）

---

## 附录：框架层面改进建议

### 1. 编译管线引入 Plan 层

```
用户问题
  → SemanticParser  (LLM：intent + entities)
  → Planner         (复杂度评估：SINGLE / COMPLEX / TASK)
  → Router         (根据 Plan 类型分叉)
      ├─ SINGLE   → Compiler (现有逻辑)
      ├─ COMPLEX  → Complex Query Engine
      └─ TASK     → Workflow Engine
  → ResultTransformer (结构化输出)
```

### 2. 运行时 Schema 感知

| 能力 | 现状 | 建议 |
|------|------|------|
| 空字段探测 | 无 | SchemaRegistry 记录字段空值率，空字段不作为过滤条件 |
| 字段回退 | 无 | 每个 entity type 配置回退字段链 |
| 表间关联 | 硬编码 | SchemaExplorer 自动发现表间关系（通过文档 ID 关联） |

### 3. 单元测试覆盖方向

| 场景 | 测试用例 | 当前状态 |
|------|---------|---------|
| 单患者多表提取 | 查白浩的首页+诊断+检验 | ❌ 0 命中 |
| 无数据字段作为条件 | total_src 为空时查血常规 | ❌ 0 命中 |
| 诊断字段回退 | diagnosis_name 为空时 fallback | ❌ 无测试 |
| Planner 分叉 | COMPLEX 路由到不同路径 | ❌ Planner 未接入 |

---

## 问题七：科研取数场景的架构定位（Workflow Engine vs Complex Query Engine）

### 现象

科研取数需求（基于 PID+VID 的结构化数据提取）提出来之后，一个自然的问题是：**为什么不让现有的 Workflow Engine 来处理？**

### 对比分析：Workflow Engine 的三模式

查看 `WorkflowEngine.java`，现有三种模式的工作方式：

| 模式 | 示例 | 工作机制 |
|------|------|---------|
| **PIVOT** | "发烧的患者白细胞" | Step 1 查患者群 → 提取 patient_id 列表 → Step 2 注入 DSL 过滤后查明细 |
| **MULTI_CHAIN** | "糖尿病患者的并且高血压患者" | 链式执行，步间传递 patient_id 列表，逐级收窄人群 |
| **TREND_COMPARE** | "上月vs本月门诊量" | 改 NL 查询的时间词为具体日期 → 编译两次 → 对比展示 |

**共同模式**：
- 每步输入 = **自然语言** → `compiler.compile()`（走 LLM）
- 步间传递 = **patient_id 列表**
- 目的 = **多患者群体的筛选/统计/对比**

### 科研取数的本质不同

科研取数（如「术后体温监测数据提取」）不是"多患者筛选"，是**单患者的结构化数据提取**：

```
给定 PID=002680203800 VID=1
→ Step 1: 查 shoushujilu → 拿到 operation_time = "2026-03-23"
→ Step 2: 查 hulitizhengyangli, 条件: record_time > "2026-03-23" AND vital_type_name="体温"
→ Step 3: 判断有没有 > 37.3℃ 的？
   ├─ 有 → 只取异常的
   └─ 无 → 全取
→ Step 4: 拼接成 "2024-04-19 07:30 体温：37.4℃" 格式输出
```

关键区别：

| 维度 | Workflow Engine | 科研取数 |
|------|----------------|---------|
| **输入** | 自然语言（NL） | 结构化参数（PID+VID） |
| **步间传递** | patient_id 列表 | **字段值**（手术时间、体温阈值） |
| **数据依赖** | "A 患者群 ∩ B 患者群"（AND 链式） | "手术时间→过滤体温→判断异常→分支"（值依赖） |
| **条件逻辑** | 无分支 | **if/else 分支**（异常存在则不异常则全取） |
| **每步用 LLM** | ✅ 是（走 `compiler.compile()`） | ❌ 否（固定字段，模板驱动） |
| **结果** | 统计表格/对比 | 结构化章节数据拼接 |
| **目标对象** | 多患者群体 | 单个患者 |

### 根因

把科研取数塞进 Workflow Engine 会引入三个矛盾：

**矛盾 1：NL 输入的代价不必要**

Workflow 每步都走 `compiler.compile()`（NL → LLM → DSL）。但科研取数的每一步要查哪个表、哪个字段、加什么过滤条件，是**固定的**——不需要每次让 LLM 判断。硬走 LLM 路径会：增加延迟（每次 800ms+），增加 token 消耗，增加解析失败风险。

**矛盾 2：步间数据模型不兼容**

WorkflowStep 的数据传递是 `List<String> patientIds`。但科研取数的步间传递是**具体值**——手术时间（字符串）、体温阈值比较结果（布尔值）、报告数量（整数）。后者需要不同的数据通道。

**矛盾 3：条件分支逻辑缺失**

Workflow 的三模式都是线性管线（PIVOT=2步，MULTI_CHAIN=N步，TREND_COMPARE=2步）。科研取数需要的是 **有向无环图（DAG）**——Step 2 的结果决定走 Step 3a 还是 Step 3b。Workflow Engine 的 `WorkflowStep.Status` 只有 RUNNING/COMPLETED/FAILED，没有 BRANCH_A/BRANCH_B。

### 解决思路

**不开发「新引擎」，而是新增「Complex Query Engine」作为独立管线。**

```
                     ┌─ Compiler (v2) ── 单 DSL 查询
NL ─→ ChatController ── Workflow Engine ── 人群筛选/对比
                     │
PID+VID ─→ ResearchController ── Complex Query Engine ── 单个患者结构化提取
```

**设计原则**：
1. **不要新轮子** — `EsRestClient`、`SchemaRegistry`、`DSLRenderer` 全部复用
2. **声明式** — 每份科研取数需求 = 一份 YAML 配置文件，不写 Java 代码
3. **与 Workflow Engine 不冲突** — 走独立 API 入口 `POST /api/research/extract`

**需要新增的组件**（相对较小）：

| 组件 | 职责 | 代码量估计 |
|------|------|-----------|
| `YamlStepParser` | 解析 YAML 提取步骤定义 | ~100 行 |
| `ConditionEngine` | 条件分支执行（if_else / threshold / exists） | ~80 行 |
| `ValueChannel` | 跨步骤值传递（代替 patient_id 列表） | ~60 行 |
| `TimeArithmetic` | 相对时间计算（近7天、取3天等） | ~100 行 |
| `ChapterFormatter` | 结构化学节数据拼接 | ~80 行 |

**API 入口**：

```json
POST /api/research/extract
{
  "patient_id": "002680203800",
  "visit_id": "1",
  "template": "术后数据提取"
}
```

内部调用链：

```
YAML 模板 → 解析 ExtractStep 列表 → 拓扑排序 → 逐 Step 执行
                                            │
                                    ┌───────┴────────┐
                                    │                │
                               ConditionEngine   Compiler 组件
                               (分支判断)      (EsRestClient,
                                                DSLRenderer,
                                                SchemaRegistry)
                                    │                │
                                    └───────┬────────┘
                                            │
                                      ResultAggregator
                                      (合并 → 结构化 JSON)
```

### 与现有架构的关系

这不是替换 Workflow Engine，也不是给 Compiler 加补丁。三个引擎各管各的：

| 引擎 | 输入形式 | 解决的问题 | 数据流向 |
|------|---------|-----------|---------|
| Compiler | 自然语言 | "糖尿病患者有多少？" | NL → DSL → ES |
| Workflow Engine | 自然语言 | "发烧的患者白细胞水平和血糖水平" | NL → 多步 Compiler → 人群交并集 |
| Complex Query Engine | PID+VID | "给我这个患者的术后体温+入院记录+检验报告" | 模板 → 多步 DSL → 结构化数据 |

三个引擎各有独立入口、独立数据模型、独立路由逻辑。底层的 ES 操作工具（`EsRestClient`、`SchemaRegistry`）共享。

---

*本文档基于实际测试发现的 7 个问题。问题一和问题二是最严重的——直接导致合法查询 0 命中。问题三已确认留后设计。问题四~七是架构层面的可优化点。*
