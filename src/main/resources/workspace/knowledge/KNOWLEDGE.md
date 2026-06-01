# ESMind Knowledge Base

> 索引: `history2026_clinical_inhistory_0429122358` (ES 6.5.4)
> 文档数: 53,461 | Schema 字段数: 53,687 | Nested 表: 3,469

Schema 已自动加载并缓存，查询时不需要手动查 mapping。系统通过 BusinessSemanticRegistry（25 核心表）+ SchemaRegistry（兜底）完成表名映射。

## 查询入口

`POST /api/chat` 或 `POST /api/chat/detail`

## 核心业务表（25 张）

| 表名 | 业务名 | 类型 | 别名 |
|------|-------|------|------|
| `binganshouye` | 病案首页 | object | 首页、frontpage |
| `shouyezhenduan` | 首页诊断 | nested | 诊断、住院诊断、frontpage_diag |
| `menzhenzhenduan` | 门诊诊断 | object | 门诊诊断 |
| `menzhenjiuzhenjilu` | 门诊就诊记录 | object | 门诊、就诊、outpatient |
| `jianyanbaogaofu` | 检验报告 | nested | 化验、检验、lab |
| `jianchabaogaofu` | 检查报告 | nested | 检查、exam |
| `shoushujilu` | 手术记录 | nested | 手术、surgery |
| `menzhenxiyichufang` | 门诊医嘱 | nested | 门诊医嘱、处方、药品、prescription |
| `binglizhenduan` | 病理报告 | nested | 病理、pathology |
| `hulitizhengyangli` | 体温记录 | nested | 体温、temperature |
| `chuyuanxiaojiexin` | 出院小结 | nested | 出院、discharge |
| `ruyuanjilu` | 入院记录 | object | 入院、admission |
| `menzhenshuju` | 门诊数据 | nested | 门诊资料 |
| `shouyeshoushu` | 首页手术 | nested | 首页手术 |
| `yizhu` | 医嘱 | nested | 医嘱（住院） |
| `zhuyuanfeiyongmingxi` | 住院费用 | nested | 费用（YAML 中为 zhuyuanfeiyong） |
| `hulipdayizhu` | 护理PD医嘱 | nested | 护理医嘱 |
| `hulichuruliangjilu` | 护理出入量记录 | nested | 出入量 |
| `menzhenfeiyongmingxi` | 门诊费用明细 | nested | 门诊费用 |
| `yangbenkufu` | 样本库 | nested | 样本 |
| `shoumashuzhongyongyao` | 术中用药 | nested | 术中用药 |
| `shoumamazuijilu` | 麻醉记录 | nested | 麻醉 |
| `structureddatafu` | 结构化数据 | nested | 结构化 |
| `BLWS` | 病历文书 | nested | 文书 |
| `chaoshengexamfu` | 超声检查 | nested | 超声、B超 |

## 查询模式

1. **诊断搜索**：LLM 自动跨表搜索（住院诊断 + 门诊诊断 + 门诊数据），用 SHOULD 组合
2. **时间过滤**：只应用到主动查询表（非诊断表），支持 RELATIVE（近N天）和 ABSOLUTE（yyyy-MM-dd）
3. **聚合查询**：支持 count、date_histogram（按月分布）、terms
4. **Object 表 exists**：使用具体时间字段（如 `ruyuanjilu.admission_time`）而非表名，避免空壳记录

## 自动表发现（SchemaExplorer）

系统启动时自动扫描全部 3,469+ 张 nested/object 表，对每张表自动推断业务分类：

| 推断分类 | 匹配条件 | 示例表 |
|---------|---------|-------|
| diagnosis | 含 diagnosis_name 字段 | shouyezhenduan, menzhenzhenduan |
| order | 含 order_item_name 字段 | yizhu, menzhenxiyichufang, menzhenjizhenorder |
| lab | 含 lab_item_name 字段 | jianyanbaogaofu |
| exam | 含 norm_exam_item_name 字段 | jianchabaogaofu, chaoshengexam |
| surgery | 含 operation_name 字段 | shoushujilu, shouyeshoushu |
| fee | 含 charge_fee 字段 | zhuyuanfeiyongmingxi, menzhenfeiyongmingxi |

查询时找不到表时自动 fallback：BusinessSemanticRegistry → SchemaRegistry → **SchemaExplorer 关键词匹配**
