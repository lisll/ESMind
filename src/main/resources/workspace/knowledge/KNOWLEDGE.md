# ESMind Knowledge Base

## 索引概览
| 属性 | 值 |
|------|-----|
| 索引名 | `ddd_clinical_inhistory_0812112623` |
| ES 版本 | 6.5.4 (mapping type: `typemr`) |
| 文档 | 15,585 (全部住院) |
| 结构 | 每文档=一个就诊次 |

## 关键约束
- `_source.includes = ["patient*"]` — 仅 patient 字段在 _source 中
- object 表: 直接 field 查询 (binganshouye, patient, ruyuanjilu, chuyuanjilu, shoucibingchengjilu)
- nested 表: 需 `nested` 查询 + `inner_hits` (shouyezhenduan, yizhu, jianyanbaogaofu, jianchabaogaofu)
- docvalue_fields: nested 数据用 keyword 子字段 (.accurate/.raw/.keyword) 和 date 类型

## 常用查询路径
| 含义 | ES 字段 |
|------|---------|
| 患者ID | `patient.patient_id` (text) |
| 性别 | `binganshouye.sex` (text, ngram analyzer) |
| 入院时间 | `binganshouye.admission_time` (date) |
| 出院时间 | `binganshouye.discharge_time` (date) |
| 诊断名 | `shouyezhenduan.diagnosis_name` → .accurate |
| ICD编码 | `shouyezhenduan.diagnosis_code` → .accurate |
| 主诊断标志 | `shouyezhenduan.main_diagnosis` → .raw |
| 医嘱项目 | `yizhu.order_item_name` → .accurate |
| 检验细项 | `jianyanbaogaofu.lab_sub_item_name` → .accurate |
| 检查项目 | `jianchabaogaofu.exam_item_name` → .accurate |
| 就诊类型 | `patient.wendangleixing` (1=门诊,2=住院) |
| 文书全文 | `*_src` 字段 (text, match查询) |

## 查询模板

### object 查询 (直接)
```json
{"query": {"match": {"binganshouye.sex": "男"}}}
{"query": {"range": {"binganshouye.admission_time": {"gte": "2024-01-01"}}}}
```

### nested 查询
```json
{"_source": false, "query": {"nested": {"path": "shouyezhenduan", "query": {"match": {"shouyezhenduan.diagnosis_name": "脑梗死"}}, "inner_hits": {"_source": false, "docvalue_fields": [{"field": "shouyezhenduan.diagnosis_name.accurate", "format": "use_field_mapping"}]}}}}
```

### 组合查询
```json
{"query": {"bool": {"must": [{"range": {"binganshouye.admission_time": {"gte": "2024-01-01"}}}, {"nested": {"path": "shouyezhenduan", "query": {"match": {"shouyezhenduan.diagnosis_name": "脑梗死"}}, "inner_hits": {}}}]}}}
```
