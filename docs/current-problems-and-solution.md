# ESMind 当前问题与解决思路

## 一、核心问题：被动修复的"鹦鹉"模式

每发现一个查询不准就修一个别名，修不完。根源：

### 1.1 YAML 静态配置天然有偏差

`table-semantic.yaml` 是人工写的 25 张表，错了也没人知道：

| 表名 | YAML 里写的 | 实际含义（查了 Schema） |
|------|------------|----------------------|
| `menzhenxiyichufang` | 门诊处方 | 门诊医嘱（127字段，含 order_item_name） |
| `menzhenjizhenorder` | **未注册** | 门急诊医嘱（47字段） |

别名也是人工瞎写的。别人配 YAML 时没查 ES mapping，直接把"西药处方"写上去，既丢了"医嘱"别名，又误导了后面所有人。

### 1.2 问答双方都在"猜"

```
用户问"门诊医嘱" → 猜这个表是干嘛的
我回答"≠menzhenxiyichufang" → 猜表名含义（也错了！）
两次都没有查过实际 Schema
```

### 1.3 LLM 编造字段路径

如果把所有 53,687 个字段名丢给 LLM，它更会乱编：

```
"高血压患者" → LLM 凭空编出 menzhen_shuju.blood_pressure.high_value
               （实际根本不存在这个字段）
```

LLM 擅长理解意图，**不擅长把中文意图精确映射到拼音缩写的字段名上**。你给越多字段名，它编得越离谱。

## 二、架构问题：YAML 配置与 Schema 脱节

```
系统启动时自动加载了 53,687 个字段（SchemaRegistry）
                       ↓
但查询时只用了 YAML 里人工写的 25 张表
                       ↓
YAML 漏配/错配 → 查询不准 → 人工修别名 → 还是查不准
```

Schema 自动加载了全部数据，但系统根本没用起来。

## 三、解决思路：Schema 驱动的自动业务表推断

### 3.1 核心原则

**不依赖人工配别名，程序从字段名模式自动推断业务表关系。**

### 3.2 字段名自动聚类

运行时扫描全部 3,469 张 nested/object 表的字段名，根据命名模式自动归类：

| 字段名模式 | 含义 | 匹配表 |
|-----------|------|-------|
| `order_item_name` + `order_class_name` | 医嘱类 | `yizhu`, `menzhenxiyichufang`, `menzhenjizhenorder` |
| `diagnosis_name` | 诊断类 | `shouyezhenduan`, `menzhenzhenduan`, `binglizhenduan` |
| `lab_item_name` / `lab_sub_item_name` | 检验类 | `jianyanbaogaofu`, `jianyanbaogaomingxifu` |
| `operation_name` / `surgery_name` | 手术类 | `shoushujilu`, `shouyeshoushu`, ... |

### 3.3 查询流程改进

```
用户："门诊医嘱"
   ↓
SchemaRegistry 字段名模式匹配
   → 候选表1: menzhenxiyichufang (127字段，含 order_item_name)
   → 候选表2: menzhenjizhenorder (47字段，含 order_item_name)
   ↓
LLM 只做一件事：根据用户意图选候选表
   → 不再需要猜字段路径
   ↓
程序精确构建 DSL（字段路径从 Schema 拿，不是 LLM 编的）
```

### 3.4 与现有系统的关系

- **不需要向量库**、不需要 embedding 模型、不需要额外依赖
- **YAML 退化为可选覆盖**——人工可以 override 自动推断的结果，但不是必须
- **SchemaRegistry 已有全部数据**，只需要加字段名模式匹配逻辑

## 四、预期效果

| 场景 | 现在 | 改后 |
|------|------|------|
| 配新表 | 改 YAML + 重启 | 自动识别，无需配置 |
| 别名漏配 | 查不准 | 字段名模式兜底 |
| LLM 编字段 | 经常发生 | LLM 只选表，不编字段 |
| 运维成本 | 每加一个表配一次 | 零维护 |
