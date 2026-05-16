#!/bin/bash
# =============================================================================
# ESMind — 从真实 ES 集群导出测试数据到测试 ES 节点
# =============================================================================
# 注意：此索引的 _source.includes = ["patient*"]，默认 _source 只含 patient 数据。
# 必须使用 "_source": ["*"] 强制导出所有字段，否则嵌套业务表会丢失。
# =============================================================================

# ---------- 配置（请修改 ↓） ----------
SOURCE_ES="http://真实IP:9200"                         # ← 改成真实 ES 地址
TARGET_ES="http://192.168.8.59:9230"                    # 测试 ES 地址
INDEX_PATTERN="xnk20231220_clinical_inhistory_0122113057"  # ← 改成实际的索引名
MAX_DOCS=10                                             # 导出条数

# ---------- 第一步：检查源集群 ----------
echo "=== 检查源集群索引 ==="
curl -s "$SOURCE_ES/_cat/indices/$INDEX_PATTERN?format=json" | \
  python3 -c "
import sys,json
d=json.load(sys.stdin)
if d:
    print(f'索引: {d[0][\"index\"]}, 文档数: {d[0][\"docs.count\"]}, 数据量: {d[0][\"store.size\"]}')
else:
    print('❌ 索引不存在，请确认 INDEX_PATTERN')
    sys.exit(1)
"

# ---------- 第二步：确认版本 ----------
echo ""
echo "=== 源集群版本 ==="
curl -s "$SOURCE_ES/" | python3 -c "
import sys,json
d=json.load(sys.stdin)
v=d['version']['number']
print(f'ES版本: {v}')
print(f'注意: 源集群 {v} → 目标集群 6.5.4，跨版本 reindex 可能有限制')
"

# ---------- 第三步：reindex ----------
echo ""
echo "=== 开始 reindex（最多 ${MAX_DOCS} 条，使用 _source:[\"*\"] 确保嵌套数据完整）==="
curl -s -X POST "$TARGET_ES/_reindex?pretty" -H 'Content-Type: application/json' -d "
{
  \"size\": $MAX_DOCS,
  \"source\": {
    \"remote\": {
      \"host\": \"$SOURCE_ES\"
    },
    \"index\": \"$INDEX_PATTERN\",
    \"_source\": [\"*\"],
    \"query\": {
      \"match_all\": {}
    }
  },
  \"dest\": {
    \"index\": \"$INDEX_PATTERN\"
  }
}"

# ---------- 第四步：验证 ----------
echo ""
echo "=== 验证测试 ES 数据 ==="
curl -s "$TARGET_ES/_cat/indices/$INDEX_PATTERN?format=json" | \
  python3 -c "
import sys,json
d=json.load(sys.stdin)
if d:
    print(f'✅ 测试ES索引: {d[0][\"index\"]}, 文档数: {d[0][\"docs.count\"]}')
else:
    print('❌ 索引不存在')
    sys.exit(1)
"

echo ""
echo "=== 检查嵌套数据完整性（取1条看 doc_list）==="
curl -s "$TARGET_ES/$INDEX_PATTERN/_search?size=1&_source=patient.doc_list&pretty" | \
  python3 -c "
import sys,json
d=json.load(sys.stdin)
hits = d.get('hits',{}).get('hits',[])
if hits:
    src = hits[0].get('_source',{})
    doc_list = src.get('patient',{}).get('doc_list',[])
    print(f'文档ID: {hits[0][\"_id\"][:60]}...')
    print(f'doc_list 表数: {len(doc_list)}')
    print(f'包含的表: {doc_list}')
else:
    print('无数据')
"
