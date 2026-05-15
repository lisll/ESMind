---
name: query-writing
description: Builds and executes Elasticsearch DSL queries from natural language questions. Covers match, term, range, bool, aggregations, and nested queries with inner_hits support for the neuro-department index model. Use when the user asks to search, count, aggregate, filter, or analyze data.
---

# Query Writing Skill

## When to Use

Use query-writing when the user:
- Asks "find ...", "search for ...", "how many ...?"
- Wants aggregated data (count, sum, avg, min, max)
- Needs results sorted, filtered, or grouped
- Asks for trends, rankings, or comparisons

## Workflow

### Step 1 — Understand the Question

Parse the user's question into:
- **Target index** — which index holds the data?
- **Filters** — time range, status, diagnosis, patient?
- **Target table** — which business table? (binganshouye, jianyanbaogaomingxifu, shouyezhenduan, etc.)
- **Aggregation** — count, sum, group by?
- **Sorting** — by date, by value?
- **Result size** — top N, all?

### Step 2 — Explore Schema First

ALWAYS call `es_get_mapping` on the target index:
- Identify which fields are `nested` (marked with `[TABLE]` — these are business tables)
- Confirm field names and types
- Understand date format for range queries

### Step 3 — Build the DSL

Choose the query type:

| Query Type | Use When | Elasticsearch DSL |
|------------|----------|-------------------|
| Simple search | Full-text match | `{"match": {"field": "value"}}` |
| Exact filter | Exact value | `{"term": {"field.keyword": "Value"}}` |
| Range query | Numeric/date range | `{"range": {"field": {"gte": x, "lte": y}}}` |
| Multi-condition | Complex logic | `{"bool": {"must": [...], "filter": [...], "should": [...]}}` |
| Nested query | Business table fields | `{"nested": {"path": "...", "query": {...}, "inner_hits": {}}}` |

### ⚠️ CRITICAL: inner_hits for Nested Data

For the neuro index (and any index with `_source.includes = ["patient*"]`), **always add `"inner_hits": {}`** inside every `nested` query. This is the only way to get the actual business table data in results.

```json
{
  "query": {
    "nested": {
      "path": "binganshouye",
      "query": { "match": { "binganshouye.sex": "男性" } },
      "inner_hits": {}    // ← REQUIRED: without this, no table data returned
    }
  }
}
```

### Step 4 — Validate

Before executing, verify:
- Field names match the mapping exactly (use `es_get_mapping` output)
- Nested fields use `{"nested": {"path": "...", "query": {...}}}` — never query nested fields directly
- `inner_hits: {}` is present in every nested query when you need the data
- Use `.keyword` suffix for exact match on `text` fields
- Date formats in range queries match the mapping
- Aggregation field is aggregatable (not `text`)

### Step 5 — Execute and Present

Call `es_execute_query` with the DSL, then:
1. ✅ Show the DSL query in a JSON code block
2. ✅ Present the results in a readable format (the tool auto-renders inner_hits)
3. ✅ Provide a brief plain-language summary

## Templates

### Search with filters (nested business table)
```json
{
  "query": {
    "bool": {
      "must": [{ "nested": { "path": "binganshouye", "query": { "match": { "binganshouye.sex": "男性" } }, "inner_hits": {} } }],
      "filter": [{ "range": { "binganshouye.admission_time": { "gte": "2024-01-01" } } }]
    }
  },
  "size": 10,
  "_source": ["patient.id", "patient.patient_id", "patient.visit_id"]
}
```

### Diagnosis filter
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
      },
      "inner_hits": {}
    }
  },
  "_source": ["patient.id", "patient.patient_id"]
}
```

### Lab result with inner_hits
```json
{
  "query": {
    "bool": {
      "filter": [
        {
          "nested": {
            "path": "jianyanbaogaomingxifu",
            "query": { "term": { "jianyanbaogaomingxifu.lab_sub_item_name": "白细胞计数" } },
            "inner_hits": {
              "size": 10
            }
          }
        }
      ]
    }
  },
  "_source": ["patient.id"]
}
```

### Top-N aggregation
```json
{
  "size": 0,
  "aggs": {
    "top_diagnoses": {
      "nested": { "path": "shouyezhenduan" },
      "aggs": {
        "by_name": {
          "terms": { "field": "shouyezhenduan.diagnosis_name.keyword", "size": 10 }
        }
      }
    }
  }
}
```

## Error Recovery

| Symptom | Action |
|---------|--------|
| Empty results | Check filter values (case-sensitive). Verify index name. Check date range. Ensure `nested` path is correct. |
| Field not found | Re-check mapping; field may be inside a nested table (use `nested` query). |
| No inner_hits returned | You forgot `"inner_hits": {}` inside the nested query — add it. |
| Wrong counts | Aggregation field must be `keyword`, not `text`. For nested aggregations, use `nested` path. |
| Query parse error | DSL JSON syntax error. Validate JSON before executing. |
