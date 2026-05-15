# Elasticsearch 知识参考

本文档记录当前 ES 集群的索引结构，每次 Agent 启动时自动加载。
首次启动后请运行 schema-exploration skill 自动填充实际映射。

## 索引概览

_此部分将在首次 schema-exploration 后自动填充_

## ES DSL 语法速查

### 基础查询

```json
// Match 查询（全文搜索）
{ "match": { "field_name": "search text" } }

// Term 查询（精确匹配）
{ "term": { "field_name.keyword": "Exact Value" } }

// Range 查询（范围过滤）
{ "range": { "price": { "gte": 100, "lte": 500 } } }

// 存在查询
{ "exists": { "field": "field_name" } }
```

### 复合查询

```json
// Bool 查询
{
  "bool": {
    "must":     [ { "match": { "title": "search" } } ],
    "filter":   [ { "term": { "status": "active" } } ],
    "must_not": [ { "term": { "deleted": true } } ],
    "should":   [ { "match": { "tags": "featured" } } ]
  }
}
```

### 聚合查询

```json
// Terms 聚合（分组统计）
{
  "size": 0,
  "aggs": {
    "by_category": {
      "terms": { "field": "category.keyword", "size": 10 }
    }
  }
}

// Date Histogram（时间趋势）
{
  "size": 0,
  "aggs": {
    "over_time": {
      "date_histogram": {
        "field": "timestamp",
        "calendar_interval": "day"
      }
    }
  }
}

// 多指标聚合
{
  "size": 0,
  "aggs": {
    "stats": {
      "stats": { "field": "price" }
    }
  }
}
```

### 排序与分页

```json
{
  "sort": [
    { "timestamp": { "order": "desc" } },
    { "_score": { "order": "desc" } }
  ],
  "from": 0,
  "size": 20
}
```

### 常用查询模式

| 场景 | DSL 要点 |
|------|---------|
| 全文搜索 | `match` 或 `match_phrase` |
| 精确过滤 | `term` + `.keyword` 字段 |
| 多条件组合 | `bool` + must/filter/should |
| 时间范围 | `range` + `timestamp` 字段 |
| 计数统计 | `size: 0` + `aggs` |
| 去重统计 | `cardinality` 聚合 |
| Top N | `terms` 聚合 + `size` + `order` |
