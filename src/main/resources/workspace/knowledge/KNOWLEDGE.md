# ESMind Knowledge Base — 真实数据模型

> 索引: `history2026_clinical_inhistory_0429122358` (ES 6.5.4)
> 文档数: 38,515 | 抽取模型 V2.0-20250728 | 结构化模型 V1.2-20260206

## 顶层结构 (224 fields: 159 nested + 30 object + 35 plain)

每个文档表示一次就诊，由 object 主表和 nested 子表组成。

### 主表 (Object)

| 表名 | 字段数 | 说明 |
|------|--------|------|
| `menzhenjiuzhenjilu` | 173 | 门诊就诊记录 — 所有查询的入口，含患者基本信息 |
| `binganshouye` | 472 | 病案首页（住院） |
| `ruyuanjilu` | 118 | 入院记录 |
| `chuyuanjilu` | 91 | 出院记录 |
| `24hneiruchuyuanjilu` | 91 | 24小时内入出院记录 |
| `shoucibingchengjilu` | 70 | 首次病程记录 |
| `siwangjilu` | 73 | 死亡记录 |

### 关键 Nested 子表

| 路径 | 业务 | 查询模板 |
|------|------|---------|
| `shouyezhenduan` | 住院诊断 | `disease` → nested_match on `diagnosis_name` |
| `menzhenshuju > diagnosis_name` | 门诊诊断（双层 nested） | `disease` → nested_match on `diagnosis_name.diagnosis_name` |
| `menzhenxiyichufang` | 门诊处方 | `medicine` → nested_match on `order_item_name` |
| `jianyanbaogaofu` | 检验报告 | `lab_item` → nested_match on `lab_item_name` |
| `yizhu` | 医嘱 | `medicine` → nested_match on `order_item_name` |
| `structureddatafu` | 结构化数据 | 通用查询 |
| `shoushujilu` | 手术记录 | `surgery` |
| `chaoshengexam` | 超声检查 | `exam` |

### 核心字段路径

```
menzhenjiuzhenjilu.name             — 患者姓名
menzhenjiuzhenjilu.patient_id       — 患者ID
menzhenjiuzhenjilu.visit_dept_name  — 就诊科室
menzhenjiuzhenjilu.visit_time       — 就诊时间
menzhenjiuzhenjilu.visit_type_name  — 就诊类型（门诊/住院/急诊）
menzhenjiuzhenjilu.visit_doctor_name — 就诊医生
menzhenjiuzhenjilu.age              — 年龄
menzhenjiuzhenjilu.sex_name         — 性别

shouyezhenduan.diagnosis_name       — 住院诊断名称
shouyezhenduan.norm_diagnosis_name  — 标准化诊断名称
shouyezhenduan.main_diagnosis       — 主要诊断标记

menzhenxiyichufang.order_item_name  — 门诊处方药品名
menzhenxiyichufang.order_begin_time — 开单时间

jianyanbaogaofu.norm_lab_item_name  — 标准检验项目名
jianyanbaogaofu.lab_item_name       — 检验项目名

patient.doc_list                    — 文档类型列表（含 doc_name）
total_src                           — 全文搜索（所有字段汇总）
```

## 查询模式

1. **诊断查询**: nested query on `shouyezhenduan.diagnosis_name`（住院）或 `menzhenshuju.diagnosis_name.diagnosis_name`（门诊）
2. **处方查询**: nested query on `menzhenxiyichufang.order_item_name`
3. **检验查询**: nested query on `jianyanbaogaofu.norm_lab_item_name`
4. **科室查询**: match_phrase on `menzhenjiuzhenjilu.visit_dept_name`
5. **患者ID查询**: match_phrase on `menzhenjiuzhenjilu.patient_id`
6. **时间范围**: range on `menzhenjiuzhenjilu.visit_time`
