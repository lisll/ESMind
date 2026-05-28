# ESMind — 高性能医疗 Elasticsearch Query Compiler

> 最后更新：2026-05-28

将自然语言医疗查询编译为 Elasticsearch DSL 并返回结构化结果。  
不依赖 LLM 推理生成 DSL，采用 **SemanticIR** 作为唯一内部查询语言，通过 Resolution → ASTBuilder → DSLRenderer 的编译管线执行。

## 架构总览

```
用户输入 (NL)
    │
    ├─── FastPath (QueryDictionary, <100ms)
    │         ↓
    └─── SlowPath (LLM Entity Extraction, 3-5s)
              ↓
     SemanticIR（唯一内部查询语言）
         │ Entity: type + value + clauseType + table + field
         ↓
    Resolution（TemplateEngine.resolve）
         │ 填充编译字段：clauseType/table/field/keyword
         ↓
    ASTBuilder（无状态，纯机械转换）
         │ 按 table 分组 → BoolNode + NestedNode
         ↓
    DSLRenderer → JSON DSL
         ↓
    ES Client → 执行 → ResultTransformer
```

## 核心组件

| 组件 | 路径 | 职责 |
|------|------|------|
| `SemanticIR` | `semantic/` | 唯一内部查询语言。Entity 自包含语义+编译两层字段 |
| `SemanticParser` | `semantic/` | LLM 调用，实体抽取。输出 SemanticIR（仅语义层字段） |
| `TemplateEngine` | `template/` | Resolution 阶段：补全 Entity 编译字段。含 TABLE_TIME_FIELDS 映射 |
| `ASTBuilder` | `ast/` | 纯机械转换，无业务逻辑。按 table 分组构建 NestedNode |
| `DSLRenderer` | `renderer/` | AST → JSON DSL，无状态 |
| `QueryValidator` | `validator/` | DSL 合法性校验 |
| `QueryDictionary` | `normalizer/` | FastPath 规则词典（待实现） |
| `EsRestClient` | `compiler/` | ES HTTP 通信 |
| `SchemaRegistry` | `compiler/` | ES mapping 加载 + 缓存 |
| `ResultTransformer` | `renderer/` | ES 结果 → Markdown 表格 |

## SemanticIR 数据结构

```json
{
  "version": 1,
  "intent": "patient_search",
  "entities": [
    {
      // 语义层（解析器输出）
      "type": "disease",
      "value": "高血压",
      // 编译层（Resolution 填充）
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
LLM Path:  NL → SemanticParser(LLM) → SemanticIR(部分) → TemplateEngine.resolve → SemanticIR(完整) → ASTBuilder → DSL
FastPath:  NL → QueryDictionary → SemanticIR(完整) → ASTBuilder → DSL
```

## 时间字段映射

23 个业务表的时间字段在 `TemplateEngine.TABLE_TIME_FIELDS` 中定义。  
Resolution 阶段根据查询涉及的 nested 表自动注入对应的时间字段。  
详见 `esmind-table-time-fields` skill。

## 快速开始

```bash
# 运行 Spring Boot 服务
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8090"

# 查询测试
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question":"高血压患者近150天有手术且近120天有检验报告"}'
```

## 索引信息

- 索引: `history2026_clinical_inhistory_0429122358` (ES 6.5.4)
- 文档数: 38,515
- 主要 nested 表: jianyanbaogaofu(检验)、shouyezhenduan(诊断)、menzhenxiyichufang(处方)、shoushujilu(手术)、binglizhenduan(病理)
