# ESMind Knowledge Base — Elasticsearch Index 字段映射与查询指南

## 索引概览

| 属性 | 值 |
|------|-----|
| 索引名 | `xnk20231220_clinical_inhistory_0122113057` |
| ES 版本 | 6.x（mapping type: `typemr`） |
| 结构 | 一个索引，每个文档对应一个患者，所有数据在嵌套对象中 |
| dynamic | `strict`（只允许 mapping 中定义的字段） |
| 总嵌套对象数 | 189 |
| 总字段数 | 224（35 个直属字段 + 189 个嵌套/对象字段） |

## 核心查询模式

### 1. 所有嵌套对象都需要用 `nested` 查询

ES 6.x 中，`nested` 类型字段必须用 `nested` 查询：

```json
{
  "query": {
    "nested": {
      "path": "binganshouye",
      "query": {
        "term": { "binganshouye.patient_id": "P001" }
      }
    }
  }
}
```

### 2. 患者筛选

```json
{
  "query": {
    "bool": {
      "filter": [
        { "nested": { "path": "patient", "query": { "term": { "patient.id": "患者ID" } } } }
      ]
    }
  }
}
```

### 3. 时间范围

```json
{
  "range": { "binganshouye.admission_time": { "gte": "2024-01-01", "lte": "2024-12-31" } }
}
```

---

## 各表字段映射

### 一、病案首页 (`binganshouye`)

473 个字段，覆盖患者入院基本信息。关键字段：

| 中文名 | ES 字段名 | 类型 | 说明 |
|--------|----------|------|------|
| 患者ID | `patient_id` | ID | 主键，关联患者 |
| 就诊次 | `visit_id` | ID | 关联就诊 |
| 医院编码 | `hospital_code` | ID | 医院标识 |
| 性别 | `sex_name` | enum | 男性/女性/未知的性别 |
| 年龄 | `age` | text | 入院时年龄 |
| 年龄单位 | `age_unit` | text | 岁/月/天 |
| 出生日期 | `date_of_birth` | date | 格式: yyyy-MM-dd |
| 职业 | `occupation_name` | massenum | 职业分类 |
| 入院时间 | `admission_time` | date | 入院日期时间 |
| 出院时间 | `discharge_time` | date | 出院日期时间 |
| 入院确诊日期 | `confirm_diag_date` | date | 确诊日期 |
| 入院科室 | `admission_dept_name` | massenum | 入院科室名称 |
| 出院科室 | `discharge_dept_name` | massenum | 出院科室名称 |
| 出院诊断 | `discharge_diag_name` | text | 主要诊断名称 |
| 出院诊断编码 | `discharge_diag_code` | text | ICD 编码 |
| 入院日常生活能力评分 | `adl_1` | text | ADL 入院评分 |
| 出院日常生活能力评分 | `adl_2` | text | ADL 出院评分 |
| 呼吸机使用时间 | `ventilator_use_hours` | num | 单位: 小时 |
| 住院天数 | `in_hosp_days` | num | |
| 入院途径 | `admission_route_name` | massenum | 急诊/门诊/转诊 |
| 医疗付款方式 | `pay_way_name` | massenum | 医保类型 |
| 入院病情 | `adm_condition_name` | massenum | |
| 离院方式 | `discharge_way_name` | massenum | 医嘱离院/转院/死亡等 |
| 是否有出院31天内再住院计划 | `readmission_plan` | enum | |
| 门诊诊断名称 | `outp_diag_name` | text | |
| 病理诊断名称 | `patho_diag_name` | text | |
| 损伤中毒外部原因 | `injury_ext_cause` | text | |
| 药物过敏 | `drug_allergy_name` | massenum | |
| 新生儿出生体重 | `birth_weight` | num | 单位: g |
| 新生儿入院体重 | `newborn_adm_weight` | num | |

### 二、首页诊断 (`shouyezhenduan`)

出院诊断列表（编目后数据）。结构字段：

| 中文名 | ES 字段名 | 类型 |
|--------|----------|------|
| 患者ID | `patient_id` | ID |
| 就诊次 | `visit_id` | ID |
| 诊断类别 | `diagnosis_category` | massenum | 
| 诊断编码 | `diagnosis_code` | text | ICD 编码 |
| 诊断名称 | `diagnosis_name` | text | 诊断中文名 |
| 确诊标志 | `confirmed_diag_ind` | text | |
| 入院病情 | `adm_condition_code` | text | |
| 诊断病情描述 | `adm_condition_desc` | text | |
| 病理分型 | `classification_of_disease` | text | |
| 诊断依据 | `basis` | text | |
| 治疗效果 | `treatment_outcome` | text | |

### 三、病历诊断 (`binglizhenduan`)

入院时的病历诊断（与首页诊断不同，这是真正的临床诊断）：

| 中文名 | ES 字段名 | 类型 |
|--------|----------|------|
| 诊断类别 | `diagnosis_category` | massenum | 主要诊断/其他诊断 |
| 诊断编码 | `diagnosis_code` | text | |
| 诊断名称 | `diagnosis_name` | text | |
| 确诊标志 | `confirmed_diag_ind` | text | |
| 诊断日期 | `diagnosis_date` | date | |
| 病理分型 | `classification_of_disease` | text | |

### 四、检验报告 (`jianyanbaogao` / `jianyanbaogaomingxi`)

检验数据分 **主表** + **明细** 两层结构：

**检验主表 (jianyanbaogaozhubiao)** — 每张检验单一条记录：
| 中文名 | ES 字段名 | 类型 |
|--------|----------|------|
| 检验项目名称 | `lab_item_name` | massenum | 如：血常规、生化全项、凝血四项 |
| 报告号 | `report_no` | text | |
| 申请号 | `lab_apply_no` | shorttext | |
| 申请时间 | `apply_time` | date | |
| 报告时间 | `report_time` | date | |
| 患者ID | `patient_id` | ID | |
| 就诊次 | `visit_id` | ID | |
| 采样时间 | `sample_time` | date | |
| 样本类型 | `speciman_type_name` | enum | 血/尿液/静脉血等 |

**检验明细 (jianyanbaogaomingxi)** — 每个检验指标一条记录：
| 中文名 | ES 字段名 | 类型 |
|--------|----------|------|
| 检验细项名称 | `lab_sub_item_name` | massenum | 如：白细胞计数、血红蛋白、ALT |
| 检验定量结果值 | `lab_result_value` | num | 数值结果 |
| 检验定量结果单位 | `lab_result_value_unit` | text | 如：10^9/L, g/L |
| 参考范围 | `ranges` | text | 如：3.5-9.5 |
| 检验定性结果 | `lab_qual_result` | text | 阴性/阳性等 |
| 检验结果状态 | `result_status_name` | massenum | 正常/异常/危急 |
| 检验细项英文名 | `lab_sub_item_en_name` | massenum | 如：WBC, RBC, HGB |

**⚠️ 查询模式：典型的明细查询**
```json
{
  "query": {
    "nested": {
      "path": "jianyanbaogaomingxi",
      "query": {
        "bool": {
          "filter": [
            { "term": { "jianyanbaogaomingxi.lab_sub_item_name": "白细胞计数" } },
            { "term": { "patient_id": "P001" } }
          ]
        }
      }
    }
  },
  "_source": "jianyanbaogaomingxi.lab_result_value"
}
```

> 💡 也可以直接使用融合表 `jianyanbaogaomingxifu`（门诊+住院融合），查询模式相同。

### 五、神经科调取规范 — 检验指标对照

以下是神经科调取规范中要求的检验变量在 ES 中的查询方式：

#### 血常规（24项）
均在 `jianyanbaogaomingxi.lab_sub_item_name` 中按名称查询：

| 中文名 | ES 中 lab_sub_item_name | 结果字段 |
|--------|------------------------|---------|
| 白细胞计数 | 白细胞计数 | `lab_result_value` |
| 嗜中性粒细胞绝对值 | 嗜中性粒细胞绝对值 | `lab_result_value` |
| 嗜中性粒细胞百分比 | 嗜中性粒细胞百分比 | `lab_result_value` |
| 淋巴细胞绝对值 | 淋巴细胞绝对值 | `lab_result_value` |
| 淋巴细胞百分比 | 淋巴细胞百分比 | `lab_result_value` |
| 单核细胞绝对值 | 单核细胞绝对值 | `lab_result_value` |
| 单核细胞百分比 | 单核细胞百分比 | `lab_result_value` |
| 嗜酸性粒细胞绝对值 | 嗜酸性粒细胞绝对值 | `lab_result_value` |
| 嗜酸性粒细胞百分比 | 嗜酸性粒细胞百分比 | `lab_result_value` |
| 嗜碱性粒细胞绝对值 | 嗜碱性粒细胞绝对值 | `lab_result_value` |
| 嗜碱性粒细胞百分比 | 嗜碱性粒细胞百分比 | `lab_result_value` |
| 红细胞 | 红细胞 | `lab_result_value` |
| 血红蛋白 | 血红蛋白 | `lab_result_value` |
| 红细胞压积 | 红细胞压积 | `lab_result_value` |
| 平均红细胞体积 | 平均红细胞体积 | `lab_result_value` |
| 平均红细胞血红蛋白 | 平均红细胞血红蛋白 | `lab_result_value` |
| 平均红细胞血红蛋白浓度 | 平均红细胞血红蛋白浓度 | `lab_result_value` |
| 红细胞分布宽度变异系数 | 红细胞分布宽度变异系数 | `lab_result_value` |
| 红细胞分布宽度SD | 红细胞分布宽度-SD | `lab_result_value` |
| 血小板计数 | 血小板计数 | `lab_result_value` |
| 血小板分布宽度 | 血小板分布宽度 | `lab_result_value` |
| 平均血小板体积 | 平均血小板体积 | `lab_result_value` |
| 大型血小板比率 | 大型血小板比率 | `lab_result_value` |
| 血小板压积 | 血小板压积 | `lab_result_value` |

#### 其他检验项目
同样按 `lab_sub_item_name` 查询：

| 检验组 | lab_item_name | 细项在 lab_sub_item_name 中 |
|--------|---------------|---------------------------|
| 血沉 | 血沉 | ESR/血沉 |
| 尿常规 | 尿常规 | 潜血、葡萄糖、白细胞、酮体等21项 |
| 凝血+D二聚体 | 凝血四项/凝血+D二聚体 | PT、APTT、TT、FIB、D-二聚体定量 |
| 生化全项 | 生化全项 | 葡萄糖、钾、钠、ALT、AST等36项 |
| 甲功七项 | 甲功七项 | TT3、TT4、FT3、FT4、TSH等7项 |
| 术前免疫八项 | 术前免疫 | 乙肝五项、丙肝、梅毒、艾滋 |
| 脑脊液生化 | 脑脊液生化 | 脑脊液蛋白定量、葡萄糖、氯 |

### 六、检查报告 (`jianchabaogaofu` / `menzhenjianchabaogao`)

影像检查（MR、CT、超声等）的数据结构：

| 中文名 | ES 字段名 | 类型 |
|--------|----------|------|
| 检查类别 | `exam_class_name` | massenum | CT/核磁/超声 |
| 检查项目 | `exam_item_name` | massenum | 头颅MR、颈椎MR等 |
| 检查所见 | `exam_feature` | text | 影像所见描述 |
| 检查结论 | `exam_diag` | text | 影像诊断结论 |
| 检查时间 | `check_time` | date | |
| 报告时间 | `report_time` | date | |
| 检查号 | `exam_no` | text | |
| 报告号 | `report_no` | text | |
| 申请科室 | `apply_dept_name` | text | |
| 患者ID | `patient_id` | ID | |
| 就诊次 | `visit_id` | ID | |

> 💡 神经科常用：`exam_item_name` = "头颅MR"、"颈椎MR"、"头颅CT"

### 七、住院病历文书 (文本全文)

病历文书存储在独立的嵌套对象中，每个文书类型有一个独立的字段：

| 文书类型 | ES 嵌套对象 | 文本内容字段 |
|---------|------------|------------|
| 入院记录 | `ruyuanjilu` | `.admission_diagnosis_src` 等 |
| 首次病程记录 | `shoucibingchengjilu` | `.src` 系列字段 |
| 日常病程记录 | `richangbingchengjilu` | `.src` 系列字段 |
| 上级医师查房 | `shangjiyishichafanglu` | `.src` 系列字段 |
| 出院小结 | `chuyuanxiaojie` | `.src` 系列字段 |
| 抢救记录 | `qiangjiujilu` | `.src` 系列字段 |
| 死亡记录 | `siwangjilu` | `.src` 系列字段 |
| 手术记录 | `shoushujilu` | `.src` 系列字段 |
| 会诊记录 | `huizhenjilu` | `.src` 系列字段 |

**病历文书也通过 `*_src` 字段直接检索全文**（35 个顶层 text 字段）：
- `binganshouye_src` — 病案首页全文本
- `ruyuanjilu_src` — 入院记录全文本
- `shoucibingchengjilu_src` — 首次病程记录
- `richangbingchengjilu_src` — 日常病程记录
- `chuyuanxiaojie_src` — 出院小结
- 等等

这些 `_src` 字段可直接用 `match` 查询做全文搜索：
```json
{
  "match": { "ruyuanjilu_src": "脑梗死" }
}
```

### 八、住院医嘱 (`yizhu`)

| 中文名 | ES 字段名 | 类型 |
|--------|----------|------|
| 医嘱内容 | `order_content_name` | massenum |
| 医嘱开始时间 | `order_start_time` | date |
| 医嘱停止时间 | `order_stop_time` | date |
| 药品名称 | `drug_name` | massenum |
| 药品规格 | `drug_spec` | text |
| 单次剂量 | `single_dose` | num |
| 剂量单位 | `dose_unit` | text |
| 给药途径 | `administration_route_name` | massenum |
| 医嘱状态 | `order_status_name` | massenum |
| 长期/临时 | `long_term_flag` | text |
| 开嘱医生 | `order_doctor_name` | text |
| 执行护士 | `execute_nurse_name` | text |

### 九、护理评估 (`pingfenbiao`, `hulipingguxinxibiao`)

包含 ADL 评分、GCS 评分、NIHSS 评分等各种量表：

| 中文名 | ES 字段名 | 说明 |
|--------|----------|------|
| 日常生活能力评分 | `adl_score` / `adl_1` / `adl_2` | ADL Barthel 指数 |
| 格拉斯哥昏迷评分 | `gcs_score` | GCS |
| NIHSS 评分 | `nihss_score` | 美国国立卫生研究院卒中量表 |
| APACHE II 评分 | `apache_2_score` | 危重症评分 |

### 十、在院患者 (`zaiyuanhuanzheliebiao`)

| 中文名 | ES 字段名 |
|--------|----------|
| 入院科室 | `adm_dept_date_time` |
| 入院病房 | `adm_ward_date_time` |
| 病床号 | `bed_label` |
| 主治医师 | `chief_physician` |
| 患者ID | `patient_id` |
| 就诊次 | `visit_id` |

---

## 常用查询模板

### 按患者查询所有信息

```json
{
  "query": {
    "bool": {
      "filter": [
        { "term": { "patient.id": "{{患者ID}}" } }
      ]
    }
  }
}
```

### 按出院诊断筛选患者（首页诊断）

```json
{
  "query": {
    "nested": {
      "path": "shouyezhenduan",
      "query": {
        "bool": {
          "filter": [
            { "match": { "shouyezhenduan.diagnosis_name": "脑梗死" } },
            { "term": { "shouyezhenduan.diagnosis_category": "主要诊断" } }
          ]
        }
      }
    }
  }
}
```

### 查询特定患者的检验结果（如白细胞计数）

```json
{
  "query": {
    "bool": {
      "filter": [
        { "term": { "patient.id": "{{患者ID}}" } },
        {
          "nested": {
            "path": "jianyanbaogaomingxi",
            "query": {
              "term": { "jianyanbaogaomingxi.lab_sub_item_name": "白细胞计数" }
            }
          }
        }
      ]
    }
  },
  "_source": ["patient.id", "jianyanbaogaomingxi.lab_result_value",
               "jianyanbaogaomingxi.lab_result_value_unit",
               "jianyanbaogaomingxi.report_time"]
}
```

### 查询 MR 检查结果

```json
{
  "query": {
    "nested": {
      "path": "jianchabaogaofu",
      "query": {
        "bool": {
          "filter": [
            { "match": { "jianchabaogaofu.exam_item_name": "头颅MR" } },
            { "term": { "patient.id": "{{患者ID}}" } }
          ]
        }
      }
    }
  }
}
```
