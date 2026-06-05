# Complex Query Engine 使用说明

## 概述

Complex Query Engine 是 ESMind v2 的新增引擎，专门用于科研取数等结构化数据提取场景。

## API 入口

### 1. 科研数据提取

**请求**:
```
POST /api/research/extract
Content-Type: application/json

{
  "patient_id": "002680203800",
  "visit_id": "1",
  "template": "术后数据提取"
}
```

**响应**:
```json
{
  "success": true,
  "research_name": "术后数据提取",
  "version": "1.0",
  "steps": [
    {
      "stepId": "surgery_time",
      "hits": [...],
      "success": true
    },
    {
      "stepId": "temperature",
      "hits": [...],
      "success": true
    },
    {
      "stepId": "admission_record",
      "hits": [...],
      "success": true
    }
  ],
  "errors": []
}
```

## 核心类结构

- `ExtractDef` - 完整的提取任务定义
- `ExtractStep` - 单个提取步骤定义
- `StepExecutor` - 步骤执行器（生成 DSL + 执行 ES 查询）
- `ConditionEngine` - 条件引擎（处理 if_else、threshold 等）
- `ComplexQueryEngine` - 主引擎（协调各步骤执行）
- `ResearchController` - API 控制器

## 实现进度

### ✅ Phase 1：核心管线可运行
- [x] `StepExecutor`：单表提取 DSL（模板驱动）
- [x] `ConditionEngine`：if_else + threshold
- [x] `ComplexQueryEngine`：线性步骤执行
- [x] API: `POST /api/research/extract` 支持内联步骤

### ⏳ Phase 2：YAML 模板系统
- [ ] YAML 解析器 + 验证
- [ ] 模板注册/管理
- [ ] 模板参数化（`{{pid}}`/`{{vid}}` 占位符）
- [ ] 常用模板预置（术后体温、检验报告...）

### ⏳ Phase 3：高级能力
- [ ] 多条手术记录处理（遍历聚合）
- [ ] 自定义格式化输出
- [ ] 结果缓存
- [ ] 审计日志

## 与现有架构的关系

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
    └─ 结构化取数 ─→ ResearchController (新增)
                        │
                  Complex Query
                    Engine (新增)
                        │
                  提取步骤定义
```
