---
name: schema-exploration
description: Lists available Elasticsearch indices, describes field mappings, and identifies index relationships. Use when the user asks about what data is available, index structure, field types, or before writing any query.
---

# Schema Exploration Skill

## When to Use

Use schema-exploration when the user:
- Asks "what indices are available?"
- Asks "what fields does index X have?"
- Asks about data structure before writing a query
- You need to understand the mapping before you can build a correct DSL query

## Workflow

### Step 1 — List All Indices

Call `es_list_indices` to see everything available.

### Step 2 — Inspect Relevant Indices

Call `es_get_mapping` with the index name(s) you need to understand:

```
Tool: es_get_mapping
indices: "my-index"              # single index
indices: "logs-*,orders"         # glob patterns
```

This returns:
- **Field names** and their data types
- **Nested structures** (object / nested)
- **Sample documents** (3 example docs)

### Step 3 — Present the Findings

Provide:
- A list of all indices with doc counts
- Field names and types for the indices the user asked about
- Relationships between indices (shared fields, join fields, parent/child)
- Sample data to illustrate what the index holds

## Examples

### "What data do you have?"

1. Call `es_list_indices`
2. Format as a readable list:
   ```
   The ES cluster has 3 indices:
    · orders       (12,450 docs)
    · products     (3,200 docs)
    · customers    (980 docs)
   ```

### "What fields does the orders index have?"

1. Call `es_get_mapping` with `indices: "orders"`
2. Present a field table:
   ```
   orders mapping:
   - order_id       (keyword)
   - customer_id    (keyword)
   - total_amount   (float)
   - status         (keyword)
   - created_at     (date)
   - items          (nested)
      - product_id  (keyword)
      - quantity    (integer)
      - price       (float)
   ```
