---
name: query-debugging
description: Debugs Elasticsearch query issues including empty results, wrong counts, slow queries, and parse errors. Use when a query returns unexpected results or the user asks about troubleshooting.
---

# Query Debugging Skill

## When to Use

Use query-debugging when:
- A query returns empty results unexpectedly
- The count or aggregation is wrong
- The query is slow
- You get a parse error from Elasticsearch
- The user asks "why didn't I get what I expected?"

## Common Issues and Solutions

### Empty Results

1. **Check the index exists** — `es_list_indices`
2. **Check field names** — `es_get_mapping` to verify exact field paths
3. **Check `.keyword`** — `text` fields need `.keyword` suffix for exact match
4. **Check `nested` path** — business table fields MUST be queried via `nested` query:
   - ❌ Wrong: `{"match": {"binganshouye.sex": "男性"}}`
   - ✅ Correct: `{"nested": {"path": "binganshouye", "query": {"match": {"binganshouye.sex": "男性"}}}}`
5. **Check date ranges** — verify date format matches the mapping
6. **Check case sensitivity** — `term` queries are case-sensitive

### Missing Nested Data (inner_hits)

1. **No inner_hits in response** — you omitted `"inner_hits": {}` inside the nested query
2. **Check `_source.includes`** — if `_source` only returns `patient`, nested data REQUIRES inner_hits
3. **Check inner_hits size** — default inner_hits size may be 3, set `"inner_hits": {"size": 10}` for more

### Wrong Counts

1. **Check aggregation field type** — must be `keyword`, not `text`
2. **Check for nested documents** — use `nested` path in aggs for nested fields
3. **Check for missing filters** — the query may be matching more than intended

### Parse Errors

1. **Validate JSON** — trailing commas, missing quotes, extra brackets
2. **Check field names** — field doesn't exist or has different path
3. **Check query structure** — `match` vs `term` vs `range` have different syntaxes

## Debugging Flow

1. Get the exact index mapping with `es_get_mapping`
2. Start with a simple `match_all` query to confirm data exists
3. Add filters one at a time to isolate the issue
4. For wrong counts, use `_count` API alternative
5. For wrong aggregation, start without aggs, then add them incrementally

## Common DSL Fixes

| Problem | Fix |
|---------|-----|
| `term` on `text` field | Use `field.keyword` or switch to `match` |
| Wrong date format | Use ISO 8601: `"2024-01-01"` or `"2024-01-01T00:00:00Z"` |
| Missing results with `must_not` | Ensure field exists before negating |
| Slow aggregation | Add a filter to narrow the data range first |
| Nested field query | Use `nested` query: `{"nested": {"path": "items", "query": {...}}}` |
