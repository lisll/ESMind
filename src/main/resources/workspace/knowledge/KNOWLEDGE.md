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
- `dynamic: strict` — 只允许 mapping 中定义的字段
- `ES _id` 格式: `{hash}{hospitalCode}##{visitType}#{patientId}#{visitId}` (2=住院)
- 日期格式: `yyyy-MM-dd HH:mm:ss`

## 数据模型总览 — 理解这个就能写好查询

医疗数据的存储方式分三层：

| 层 | ES 类型 | 表 | 说明 |
|----|---------|-----|------|
| **首页层** | object | binganshouye, patient, ruyuanjilu, chuyuanjilu, shoucibingchengjilu | 每个就诊一条，字段直接访问 |
| **明细层** | nested (行式) | jianyanbaogaofu(检验), jianchabaogaofu(检查), yizhu(医嘱), shouyezhenduan(诊断), shoushu(手术) ... | 每个就诊多条，每行一个项目 |
| **文书层** | nested | *`_src` 字段 (如 bingchengjilu_src) | 病历全文，match 查询 |

**最关键的模式：明细数据 = 嵌套表中的行记录**
```
jianyanbaogaofu 示例行:
  { lab_sub_item_name: "白细胞计数", lab_sub_item_result: "6.8", lab_unit: "10^9/L", lab_test_time: "2024-01-15 08:30" }
  { lab_sub_item_name: "血红蛋白",   lab_sub_item_result: "135",  lab_unit: "g/L",    lab_test_time: "2024-01-15 08:30" }
```

要查"白细胞计数"→ 在 jianyanbaogaofu 中 filter `lab_sub_item_name` = "白细胞计数"，取 result 和 time。

## 变量分类 → ES 查询路径

### 1. 病案首页变量（直接 field 查询，object 类型）
| 变量名 | ES 路径 | 查询方式 |
|--------|---------|---------|
| 性别 | binganshouye.sex | match |
| 年龄 | binganshouye.age | range |
| 出生日期 | patient.birth_date | range/term |
| 入院日期 | binganshouye.admission_time | range |
| 出院日期 | binganshouye.discharge_time | range |
| 确诊日期 | binganshouye.confirm_date (若存在) | range |
| ID号 | patient.patient_id | term |
| 职业 | binganshouye.occupation (若存在) | match |
| 出院诊断 | shouyezhenduan.diagnosis_name | nested+filter |
| 日常生活能力评分 | binganshouye.adl_score (若存在) | range |
| 呼吸机使用时间 | binganshouye.ventilator_time (若存在) | range |

### 2. 病历文书（nested，需 docvalue_fields）
| 变量 | 查询方法 |
|------|---------|
| 现病史 | match `bingchengjilu_src.present_illness` 或类似 |
| 既往史 | match 对应 src 字段 |
| 个人史 | match 对应 src 字段 |
| 家族史 | match 对应 src 字段 |
| 辅助检查 | match 对应 src 字段 |

### 3. 检验数据 — jianyanbaogaofu (nested, 行式存储)

所有检验变量都存为 jianyanbaogaofu 中的行，用 lab_item_name (大类) 和 lab_sub_item_name (细项) 定位。

**查询模式**:
```json
{"_source": false, "query": {"nested": {"path": "jianyanbaogaofu", "query": {"bool": {"must": [
  {"match": {"jianyanbaogaofu.lab_sub_item_name": "白细胞计数"}}
]}}, "inner_hits": {"_source": false, "docvalue_fields": [
  {"field": "jianyanbaogaofu.lab_sub_item_name.accurate", "format": "use_field_mapping"},
  {"field": "jianyanbaogaofu.lab_sub_item_result.accurate", "format": "use_field_mapping"},
  {"field": "jianyanbaogaofu.lab_test_time", "format": "yyyy-MM-dd HH:mm:ss"}
]}}}}
```

**变量到子项名的关系**（NERUOLOGY_SPEC_2025 定义的分类）：
| 检验大类 (lab_item_name) | 细项 (lab_sub_item_name) |
|--------------------------|--------------------------|
| 血常规 | 白细胞计数, 嗜中性粒细胞绝对值, 嗜中性粒细胞百分比, 淋巴细胞绝对值, 淋巴细胞百分比, 单核细胞绝对值, 单核细胞百分比, 嗜酸性粒细胞绝对值, 嗜酸性粒细胞百分比, 嗜碱性粒细胞绝对值, 嗜碱性粒细胞百分比, 红细胞, 血红蛋白, 红细胞压积, 平均红细胞体积, 平均红细胞血红蛋白, 平均红细胞血红蛋白浓度, 红细胞分布宽度变异系数, 红细胞分布宽度-SD, 血小板计数, 血小板分布宽度, 平均血小板体积, 大型血小板比率, 血小板压积 |
| 血沉 | 血沉 |
| 尿常规 | 潜血, 葡萄糖, 抗坏血酸, 白细胞, 酮体, 亚硝酸盐, 尿胆原, 胆红素, 蛋白质, 比重, 酸碱度, 红细胞, 白细胞数, 鳞状上皮, 非鳞状上皮, 透明管型, 细菌, 真菌, 滴虫, 结晶, 病理性管型 |
| 粪便常规 | 颜色, 性状, 镜检, 潜血试验 |
| 凝血II+D二聚体 | 凝血酶原时间, 凝血酶原活动度, 国际标准比率, 活化部分凝血活酶, APTT比率, 凝血酶时间, TT比率, 纤维蛋白原, D-二聚体定量 |
| 生化全项 | 葡萄糖, 钾, 钠, 氯, 二氧化碳结合率, 钙, 磷, 血清镁, 尿素, 肌酐, 尿酸, 丙氨酸氨基转移酶, 天门冬氨酸氨基转移酶, 总蛋白, 前白蛋白, 白蛋白, 球蛋白, 白球比, 总胆红素, 直接胆红素, 间接胆红素, 胆汁酸, 胆碱酯酶, 谷氨酰胺基转移酶, 碱性磷酸酶, 总胆固醇, 甘油三酯, 高密度脂蛋白, 低密度脂蛋白, 载脂蛋白A1, 载脂蛋白B, 脂蛋白（a）, 肌酸激酶, 乳酸脱氢酶, 血淀粉酶, 同型半胱氨酸 |
| 脑脊液生化 | 脑脊液蛋白定量, 脑脊液葡萄糖, 脑脊液氯 |
| 脑脊液常规 | 颜色, 透明度, 细胞总数, 白细胞数, 多核细胞百分数, 单个核细胞百分数 |
| 术前免疫八项 | 乙型肝炎表面抗原, 乙型肝炎表面抗体, 乙型肝炎e抗原, 乙型肝炎e抗体, 乙型肝炎核心总抗体, 丙型肝炎抗体, 梅毒血清特异性抗体1, 艾滋病毒抗体/P24抗原 |
| 肿瘤标志物组合 | 鳞状上皮细胞癌抗原, 甲胎蛋白, 糖类抗原15-3, 糖类抗原CA72-4, 胃泌素释放肽前体, 骨胶素CYFRA21-1测定, 神经元特异性烯醇化酶, 癌胚抗原, 糖类抗原125, 糖类抗原19-9 |
| 甲状腺功能 | 总三碘甲状腺原氨酸, 总甲状腺素, 游离三碘甲状腺原氨酸, 游离甲状腺素, 促甲状腺素, 抗甲状腺球蛋白抗体, 抗甲状腺过氧化物酶抗体 |
| 血气 | 血液酸碱度, CO2分压, 氧分压, 碳酸氢盐, 碱剩余, 血氧饱和度, 钠离子, 钾离子, 钙离子, 总二氧化碳, 红细胞压积, 血红蛋白, 标准碱剩余, 缓冲碱, 标准碳酸氢根, 标准酸碱度, 氧含量, 氢离子浓度, 动脉肺泡氧分压, 标准钙离子, 大气压, P50 |

### 4. 检查数据 — jianchabaogaofu (nested, 行式存储)
与检验类似，通过 exam_item_name 定位。如 `exam_item_name` = "头颅MR" / "颈椎MR"
```json
{"_source": false, "query": {"nested": {"path": "jianchabaogaofu", "query": {"match": {"jianchabaogaofu.exam_item_name": "头颅MR"}}, "inner_hits": {"_source": false, "docvalue_fields": [{"field": "jianchabaogaofu.exam_item_name.accurate", "format": "use_field_mapping"}]}}}}
```

## 通用查询模板

### object 查询
```json
{"query": {"match": {"binganshouye.sex": "男"}}}
{"query": {"range": {"binganshouye.admission_time": {"gte": "2024-01-01"}}}}
```

### nested 查询（单条件）
```json
{"_source": false, "query": {"nested": {"path": "shouyezhenduan", "query": {"match": {"shouyezhenduan.diagnosis_name": "脑梗死"}}, "inner_hits": {"_source": false, "docvalue_fields": [{"field": "shouyezhenduan.diagnosis_name.accurate", "format": "use_field_mapping"}]}}}}
```

### nested 查询（组合：诊断+检验+时间）
```json
{"_source": false, "query": {"bool": {"must": [
  {"range": {"binganshouye.admission_time": {"gte": "2024-01-01"}}},
  {"nested": {"path": "shouyezhenduan", "query": {"match": {"shouyezhenduan.diagnosis_name": "脑梗死"}}}},
  {"nested": {"path": "jianyanbaogaofu", "query": {"match": {"jianyanbaogaofu.lab_sub_item_name": "白细胞计数"}}}}
]}}}
```

## 行为指引
- **neruology_spec_2025.md** 是变量定义权威来源，定义"要查什么"
- **本 KNOWLEDGE.md** 是查询路径映射，定义"在哪里、怎么查"
- 检验变量都用 `lab_sub_item_name` 精确匹配，返回 result + time
- 诊断用 `diagnosis_name`，ICD 用 `diagnosis_code`
- 一次查出所有符合条件的患者，用 Markdown 表格展示
- 日期字段用 .accurate 子字段做 docvalue_fields 提取
