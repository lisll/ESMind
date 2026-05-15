---
name: query-writing
description: Builds and executes Elasticsearch DSL queries from natural language questions. Covers match, term, range, bool, aggregations, and nested queries. Use when the user asks to search, count, aggregate, filter, or analyze data.
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
- **Filters** — time range, status, category?
- **Aggregation** — count, sum, group by?
- **Sorting** — by date, by value?
- **Result size** — top N, all?

### Step 2 — Explore Schema First

ALWAYS call `es_get_mapping` on the target index:
- Confirm field names and types
- Check if fields are `.keyword` or `text`
- Understand date format for range queries

### Step 3 — Build the DSL

Choose the query type:

| Query Type | Use When | Elasticsearch DSL |
|------------|----------|-------------------|
| Simple search | Full-text match | `{"match": {"field": "value"}}` |
| Exact filter | Exact value | `{"term": {"field.keyword": "Value"}}` |
| Range query | Numeric/date range | `{"range": {"field": {"gte": x, "lte": y}}}` |
| Multi-condition | Complex logic | `{"bool": {"must": [...], "filter": [...], "should": [...]}}` |
| Aggregation | Count/Group/Stats | Add `aggs` block with `terms`, `date_histogram`, `stats` |

### Step 4 — Validate

Before executing, verify:
- Field names match the mapping exactly
- Use `.keyword` suffix for exact match on `text` fields
- Date formats in range queries match the mapping
- Aggregation field is aggregatable (not `text`)

### Step 5 — Execute and Present

Call `es_execute_query` with the DSL, then:
1. Show the DSL query in a JSON code block
2. Present the results in a readable format
3. Provide a brief plain-language summary

## Templates

### Search with filters
```json
{
  "query": {
    "bool": {
      "must": [{ "match": { "title": "keyword" } }],
      "filter": [{ "term": { "status.keyword": "active" } }],
      "range": { "created_at": { "gte": "2024-01-01" } }
    }
  },
  "size": 10,
  "sort": [{ "created_at": { "order": "desc" } }]
}
```

### Top-N aggregation
```json
{
  "size": 0,
  "aggs": {
    "top_categories": {
      "terms": { "field": "category.keyword", "size": 10 },
      "aggs": {
        "total_revenue": {
          "sum": { "field": "amount" }
        }
      }
    }
  },
  "query": { "range": { "date": { "gte": "2024-01-01" } } }
}
```

## Error Recovery

| Symptom | Action |
|---------|--------|
| Empty results | Check filter values (case-sensitive). Verify index name. Check date range. |
| Field not found | Re-check mapping; field may be `text` (use `.keyword`) or nested. |
| Wrong counts | Aggregation field must be `keyword`, not `text`. Check for duplicate docs. |
| Query parse error | DSL JSON syntax error. Validate JSON before executing. |
