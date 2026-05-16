# ESMind Knowledge Base — Elasticsearch Index 字段映射与查询指南

## 索引概览

| 属性 | 值 |
|------|-----|
| 索引名 | `xnk20231220_clinical_inhistory_0122113057` |
| ES 版本 | 6.5.4（mapping type: `typemr`） |
| 结构 | **每文档 = 一个就诊次**，同一患者的多次就诊分布在多个文档中 |
| dynamic | `strict`（只允许 mapping 中定义的字段） |
| `_source.includes` | `["patient*"]` — `_source` **只存储 patient 相关字段** |
| 总顶级字段数 | 224 |
| 其中 nested 类型 | **161 个**（业务表，如 shouyezhenduan, yizhu, jianyanbaogaofu 等） |
| 其中 object 类型 | **28 个**（如 binganshouye, ruyuanjilu, patient 等） |
| 其他直属字段 | 35 个（多为 `_src` 全文检索字段） |

## ES `_id` 格式

```
{4位hash}{hospital_code}##{visit_type}#{patient_id}#{visit_id}
例: fe37BJDXDSYY##2#000930502000#1
```

其中 `visit_type`: 1=门诊, 2=住院, 3=急诊, 4=体检, 9=其他
`hospital_code`: 如 BJDXDSYY=北医三院

## ⚠️ 关键约束：`_source` 限制与 inner_hits 问题

### `_source.includes = ["patient*"]` 的影响

ES mapping 中 `_source.includes = ["patient*"]`，意味着：

- 普通查询返回的 `_source` **只含 `patient` 对象**
- 其他嵌套对象（shouyezhenduan, yizhu, jianyanbaogaofu 等）虽被索引，但 **不存储在 `_source` 中**
- 因此标准 `nested` + `inner_hits` 查询会失败，因为 inner_hits **需要读取根文档 `_source` 来构造嵌套文档的 source**

### 解决方案：docvalue_fields

在 ES 6.5 中，使用 `docvalue_fields` 绕过 `_source` 读取：

```json
{
  "_source": false,
  "query": {
    "nested": {
      "path": "shouyezhenduan",
      "query": {"match_all": {}},
      "inner_hits": {
        "_source": false,
        "docvalue_fields": [
          {"field": "shouyezhenduan.diagnosis_name.accurate", "format": "use_field_mapping"},
          {"field": "shouyezhenduan.diagnosis_time", "format": "use_field_mapping"}
        ]
      }
    }
  }
}
```

> ⚠️ `docvalue_fields` 只支持有 `doc_values` 的字段：`keyword` 子字段和 `date` 类型。`text` 类型没有 doc_values。

### 如果 `_source` 完好的环境

在 `_source` 未损坏的 ES 版本（7.x+）或 `_source.includes` 包含嵌套路径的环境下，标准 inner_hits 仍然可用：

```json
{
  "query": {
    "nested": {
      "path": "shouyezhenduan",
      "query": {"match": {"shouyezhenduan.diagnosis_name": "脑梗死"}},
      "inner_hits": {}
    }
  },
  "_source": ["patient.id", "patient.patient_id"]
}
```

## 字段类型规则

查询方式因字段类型而异：

| 字段类型 | 查询方式 | 说明 |
|---------|---------|------|
| `nested`（161个） | `nested` 查询 + `inner_hits` | 业务表，如 shouyezhenduan, yizhu, jianyanbaogaofu |
| `object`（28个） | **直接查询**，不需要 `nested` 包装 | 如 binganshouye, ruyuanjilu, patient |
| `text` 直属字段 | 全文 `match` 查询 | 如 `binganshouye_src`, `ruyuanjilu_src` |

### object 类型 vs nested 类型对比

| | object | nested |
|---|---|---|
| 查询方式 | 直接 field 名查询 | 需 `nested` 包装 |
| 例子 | `{"range": {"binganshouye.admission_time": {...}}}` | `{"nested": {"path": "shouyezhenduan", ...}}` |
| _source 中 | 应有数据（被 includes 过滤则无） | 不在 _source 中 |
| 常见表 | binganshouye, ruyuanjilu, patient, chuyuanjilu | shouyezhenduan, yizhu, jianyanbaogaofu, pingfenbiao |

## 核心业务表列表（部分）

### 常用 nested 类型（161个）

| 嵌套对象路径 | 中文含义 | 关键字段 |
|------------|---------|---------|
| `shouyezhenduan` | 出院首页诊断 | `diagnosis_name`, `diagnosis_code`, `diagnosis_desc` |
| `yizhu` | 住院医嘱 | `order_item_name`, `order_begin_time`, `order_time` |
| `jianyanbaogaofu` | 检验报告 | `lab_item_name`, `lab_sub_item_name`, `report_time` |
| `jianyanbaogaomingxifu` | 检验报告明细 | `lab_sub_item_name`, `lab_result_value`, `lab_qual_result` |
| `jianchabaogaofu` | 检查报告 | `exam_item_name`, `exam_class_name`, `apply_time` |
| `binglizhenduan` | 病理诊断 | `diagnosis_name`, `diagnosis_code` |
| `pingfenbiao` | 护理评分表 | `apache_2_score`, `cha2ds2_vasc_score` |
| `shouyeshoushu` | 首页手术 | `operation_name`, `operation_code`, `operation_date` |
| `shoushujilu` | 手术记录 | `operation_name`, `anesthesia_method_name` |
| `richangbingchengjilu` | 日常病程记录 | (含 `_src` 文本字段) |
| `hulitizhengyangli` | 护理体征样例 | `height`, `weight`, `bmi` |
| `bingweitongzhishu` | 病危通知书 | (文书类) |
| `huizhenjilu` | 会诊记录 | (含 `_src` 文本字段) |
| `jieduanxiaojie` | 阶段小结 | (含 `_src` 文本字段) |
| `shuhoubingchengjilu` | 术后病程记录 | (含 `_src` 文本字段) |
| `shuqianxiaojie` | 术前小结 | (含 `_src` 文本字段) |
| `zhuankejilu` | 转科记录 | (含 `_src` 文本字段) |
| `structureddatafu` | 结构化数据附 | `de_code`, `de_name`, `code_value` |
| `suifangjilu` | 随访记录 | `follow_item_name`, `follow_cycle` |
| `mazuishuqianfangshijilu` | 麻醉术前访视记录 | (含 `_src` 文本字段) |

### 常用 object 类型（28个）

| 对象路径 | 中文含义 | 说明 |
|---------|---------|------|
| `patient` | 患者基本信息 | **在 _source 中**，可直接查询 `patient.patient_id` 等 |
| `binganshouye` | 病案首页 | **object 类型**，直接查询 `binganshouye.admission_time` |
| `ruyuanjilu` | 入院记录 | **object 类型**，含 `_src` 全文检索字段 |
| `chuyuanjilu` | 出院记录 | object 类型，含 `_src` |
| `shoucibingchengjilu` | 首次病程记录 | object 类型，含 `_src` |
| `menzhenjiuzhenjilu` | 门诊就诊记录 | object 类型 |

## 核心查询模式

### 1. 病案首页（object 类型 → 直接查询）

```json
{
  "query": {
    "range": {
      "binganshouye.admission_time": {
        "gte": "2025-01-01 00:00:00",
        "lte": "2025-12-31 23:59:59"
      }
    }
  }
}
```

### 2. 出院诊断（nested 类型 → nested 查询 + inner_hits + docvalue_fields）

```json
{
  "_source": false,
  "query": {
    "nested": {
      "path": "shouyezhenduan",
      "query": { "match": { "shouyezhenduan.diagnosis_name": "脑梗死" } },
      "inner_hits": {
        "_source": false,
        "docvalue_fields": [
          {"field": "shouyezhenduan.diagnosis_name.accurate", "format": "use_field_mapping"},
          {"field": "shouyezhenduan.diagnosis_code.accurate", "format": "use_field_mapping"},
          {"field": "shouyezhenduan.diagnosis_time", "format": "use_field_mapping"}
        ]
      }
    }
  }
}
```

### 3. 医嘱查询（nested 类型）

```json
{
  "_source": false,
  "query": {
    "nested": {
      "path": "yizhu",
      "query": { "match": { "yizhu.order_item_name": "护理" } },
      "inner_hits": {
        "_source": false,
        "docvalue_fields": [
          {"field": "yizhu.order_item_name.accurate", "format": "use_field_mapping"},
          {"field": "yizhu.order_begin_time", "format": "use_field_mapping"}
        ]
      }
    }
  }
}
```

### 4. 组合查询（object + nested 混合）

```json
{
  "query": {
    "bool": {
      "must": [
        { "range": { "binganshouye.discharge_time": {
            "gte": "2013-01-01 00:00:00",
            "lte": "2013-12-31 23:59:59"
        }}},
        { "nested": {
            "path": "shouyezhenduan",
            "query": { "match": { "shouyezhenduan.diagnosis_name": "心肌梗死" } },
            "inner_hits": { "size": 5 }
        }}
      ]
    }
  }
}
```

> 💡 注意：object 和 nested 字段可以混合在同一个 bool 查询中。

### 5. 全文检索（_src 字段）

```json
{
  "match": { "ruyuanjilu_src": "现病史关键词" }
}
```

## 可用 docvalue_fields 的关键字段索引

### shouyezhenduan（出院诊断）

| 字段 | docvalue 可用 | 说明 |
|------|-------------|------|
| `diagnosis_name.accurate` | ✅ keyword | 诊断名 |
| `diagnosis_code.accurate` | ✅ keyword | ICD 编码 |
| `diagnosis_desc.accurate` | ✅ keyword | 诊断描述 |
| `main_diagnosis.raw` | ✅ keyword | 是否主诊断 |
| `diagnosis_time` | ✅ date | 诊断时间 |

### yizhu（医嘱）

| 字段 | docvalue 可用 | 说明 |
|------|-------------|------|
| `order_item_name.accurate` | ✅ keyword | 医嘱项目名 |
| `order_begin_time` | ✅ date | 医嘱开始时间 |
| `order_time` | ✅ date | 医嘱开具时间 |
| `order_end_time` | ✅ date | 医嘱结束时间 |
| `basic_drugs.raw` | ✅ keyword | 基本药物标志 |

### jianyanbaogaofu（检验报告）

| 字段 | docvalue 可用 | 说明 |
|------|-------------|------|
| `lab_item_name.accurate` | ✅ keyword | 检验项目名（如"血常规"） |
| `lab_sub_item_name.accurate` | ✅ keyword | 检验细项名（如"白细胞"） |
| `lab_sub_item_en_name.accurate` | ✅ keyword | 英文细项名 |
| `report_time` | ✅ date | 报告时间 |
| `apply_time` | ✅ date | 申请时间 |

### jianchabaogaofu（检查报告）

| 字段 | docvalue 可用 | 说明 |
|------|-------------|------|
| `exam_item_name.accurate` | ✅ keyword | 检查项目名（如"头颅MR"） |
| `exam_class_name.accurate` | ✅ keyword | 检查类别名（如"超声科检查"） |
| `apply_time` | ✅ date | 申请时间 |
| `report_time` | ✅ date | 报告时间 |

### binganshouye（病案首页 — object 类型）

| 字段 | docvalue 可用 | 说明 |
|------|-------------|------|
| `admission_time` | ✅ date | 入院时间 |
| `discharge_time` | ✅ date | 出院时间 |
| `diagnosis_time` | ✅ date | 确诊时间 |
| `archiving_date` | ✅ date | 归档日期 |

## 已知限制

1. **shard 0 _source 损坏**：该索引有 30 个分片，其中 shard 0 的 `_source` 数据损坏。任何读取 `_source` 的查询（包括标准 inner_hits）会在该分片上报错。解决方案：使用 `_source: false` + `docvalue_fields`。
2. **`_source.includes = ["patient*"]`**：不支持标准的 inner_hits _source 读取。如需完整嵌套文档 source，需在 ES 7.x+ 或无此限制的索引上使用。
3. **大部分字段为 text 类型**：不支持 doc_values。只有 `keyword` 子字段和少数 `date` 字段可用 `docvalue_fields`。
4. **自定义 analyzer**：大量 text 字段使用 `my_ngram_analyzer`（N-gram 分词），用于模糊/部分匹配搜索。
