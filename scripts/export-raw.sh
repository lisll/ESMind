#!/bin/bash
# =============================================================================
# ESMind — 从真实ES导出完整就诊次数据（纯 bash/curl）
# 在 10.2.4.146 上运行
#
# 这个方法：只获取原始 JSON 响应，不做 JSON 解析。
# 打包后传回测试服务器再处理。
#
# 用法：
#   chmod +x export-raw.sh
#   ./export-raw.sh [条数]
#   例如：./export-raw.sh 10
#
# 输出：export-raw-20260516.tar.gz
# =============================================================================

set -e

ES="http://127.0.0.1:9230"
INDEX="xnk20231220_clinical_inhistory_0122113057"
MAX_DOCS="${1:-10}"
TS=$(date +%Y%m%d_%H%M%S)
OUTDIR="/tmp/esmind-export-${TS}"
OUTFILE="${HOME}/esmind-export-${TS}.tar.gz"

echo "=== ESMind 原始数据导出工具 ==="
echo "ES:     $ES"
echo "索引:   $INDEX"
echo "条数:   $MAX_DOCS"
echo "输出:   $OUTFILE"
echo ""

mkdir -p "$OUTDIR"

# ---------- Step 1: Scroll 获取文档 ID ----------
echo "[1/3] 获取文档ID..."
SCROLL_BODY='{"size":500,"_source":false,"query":{"match_all":{}},"sort":["_doc"],"stored_fields":["_none_"]}'
SCROLL_TIME="5m"

# 初始化 scroll
curl -s -X POST "$ES/$INDEX/_search?scroll=$SCROLL_TIME" \
  -H 'Content-Type: application/json' \
  -d "$SCROLL_BODY" > "$OUTDIR/scroll_response.json" 2>/dev/null

# 提取 scroll_id
SCROLL_ID=$(grep -o '"_scroll_id":"[^"]*"' "$OUTDIR/scroll_response.json" | head -1 | sed 's/"_scroll_id":"//;s/"//')

# 提取第一批 IDs
grep -o '"_id":"[^"]*"' "$OUTDIR/scroll_response.json" | cut -d'"' -f4 > "$OUTDIR/all_ids.txt"
COUNT=$(wc -l < "$OUTDIR/all_ids.txt")
echo "  已获取 $COUNT 个ID"

# 继续 scroll
while [ "$COUNT" -lt "$MAX_DOCS" ] && [ -n "$SCROLL_ID" ]; do
  PREV_COUNT=$COUNT
  curl -s -X POST "$ES/_search/scroll" \
    -H 'Content-Type: application/json' \
    -d "{\"scroll\":\"${SCROLL_TIME}\",\"scroll_id\":\"${SCROLL_ID}\"}" > "$OUTDIR/scroll_page.json" 2>/dev/null
  
  # 提取新的 scroll_id 和 IDs
  SCROLL_ID=$(grep -o '"_scroll_id":"[^"]*"' "$OUTDIR/scroll_page.json" | head -1 | sed 's/"_scroll_id":"//;s/"//')
  grep -o '"_id":"[^"]*"' "$OUTDIR/scroll_page.json" | cut -d'"' -f4 >> "$OUTDIR/all_ids.txt"
  
  COUNT=$(wc -l < "$OUTDIR/all_ids.txt")
  echo "  已获取 $COUNT 个ID"
  
  # 无新数据则退出
  if [ "$COUNT" -eq "$PREV_COUNT" ]; then
    echo "  所有文档已获取完毕"
    break
  fi
done

# 清理 scroll
curl -s -X DELETE "$ES/_search/scroll" \
  -H 'Content-Type: application/json' \
  -d "{\"scroll_id\":\"${SCROLL_ID}\"}" > /dev/null 2>&1

# 截取前 MAX_DOCS 条
head -n "$MAX_DOCS" "$OUTDIR/all_ids.txt" > "$OUTDIR/ids.txt"
TOTAL_IDS=$(wc -l < "$OUTDIR/ids.txt")
echo "  共导出 $TOTAL_IDS 个文档ID"
echo ""

# ---------- Step 2: 逐条获取完整文档 ----------
echo "[2/3] 获取完整文档（含 nested 业务表）..."

# 创建 nested 查询模板
cat > "$OUTDIR/nested_query.json" << 'QUERYEOF'
{
  "size": 1,
  "_source": true,
  "query": {
    "bool": {
      "filter": [{"ids": {"values": ["__ID__"]}}],
      "should": [
        {"nested": {"path": "shouyezhenduan", "query": {"match_all": {}}, "inner_hits": {"name": "shouyezhenduan", "size": 5000, "_source": true}}},
        {"nested": {"path": "yizhu", "query": {"match_all": {}}, "inner_hits": {"name": "yizhu", "size": 5000, "_source": true}}},
        {"nested": {"path": "jianyanbaogaofu", "query": {"match_all": {}}, "inner_hits": {"name": "jianyanbaogaofu", "size": 5000, "_source": true}}},
        {"nested": {"path": "jianchabaogaofu", "query": {"match_all": {}}, "inner_hits": {"name": "jianchabaogaofu", "size": 5000, "_source": true}}},
        {"nested": {"path": "binglizhenduan", "query": {"match_all": {}}, "inner_hits": {"name": "binglizhenduan", "size": 5000, "_source": true}}},
        {"nested": {"path": "shouyeshoushu", "query": {"match_all": {}}, "inner_hits": {"name": "shouyeshoushu", "size": 5000, "_source": true}}},
        {"nested": {"path": "shouyeshushi", "query": {"match_all": {}}, "inner_hits": {"name": "shouyeshushi", "size": 5000, "_source": true}}},
        {"nested": {"path": "jianyanbaogaomingxi", "query": {"match_all": {}}, "inner_hits": {"name": "jianyanbaogaomingxi", "size": 5000, "_source": true}}},
        {"nested": {"path": "jianchabaogaomingxi", "query": {"match_all": {}}, "inner_hits": {"name": "jianchabaogaomingxi", "size": 5000, "_source": true}}}
      ],
      "minimum_should_match": 1
    }
  }
}
QUERYEOF

mkdir -p "$OUTDIR/docs"
DOC_NUM=0

while IFS= read -r DOC_ID; do
  [ -z "$DOC_ID" ] && continue
  DOC_NUM=$((DOC_NUM + 1))
  
  # 用文档 ID 作为文件名（替换 / 等特殊字符）
  SAFE_ID=$(echo "$DOC_ID" | sed 's/[^a-zA-Z0-9._-]/_/g')
  OUTF="$OUTDIR/docs/doc_${DOC_NUM}_${SAFE_ID}.json"
  
  # 替换查询中的 __ID__
  ESCAPED_ID=$(echo "$DOC_ID" | sed 's/[\/&]/\\&/g')
  sed "s/__ID__/$ESCAPED_ID/g" "$OUTDIR/nested_query.json" > "$OUTDIR/current_query.json"
  
  printf "  [%d/%d] %s... " "$DOC_NUM" "$TOTAL_IDS" "$(echo "$DOC_ID" | head -c 50)"
  
  if curl -s -X POST "$ES/$INDEX/_search" \
    -H 'Content-Type: application/json' \
    -d @"$OUTDIR/current_query.json" > "$OUTF" 2>/dev/null; then
    
    # 检查是否有命中
    if grep -q '"hits":{"hits":\[' "$OUTF" 2>/dev/null; then
      echo "OK"
    else
      echo "❌ 未命中"
      rm -f "$OUTF"
    fi
  else
    echo "❌ curl 失败"
    rm -f "$OUTF"
  fi
  
  sleep 0.3
done < "$OUTDIR/ids.txt"

# 统计成功数
SUCCESS=$(ls "$OUTDIR/docs/"*.json 2>/dev/null | wc -l)
echo ""
echo "  成功: $SUCCESS / $TOTAL_IDS"

# ---------- Step 3: 打包 ----------
echo ""
echo "[3/3] 打包..."

# 保存一份索引信息和文档列表
echo "索引: $INDEX" > "$OUTDIR/meta.txt"
echo "ES: $ES" >> "$OUTDIR/meta.txt"
echo "导出时间: $(date)" >> "$OUTDIR/meta.txt"
echo "文档数: $TOTAL_IDS" >> "$OUTDIR/meta.txt"
echo "成功: $SUCCESS" >> "$OUTDIR/meta.txt"

ls -1 "$OUTDIR/docs/" > "$OUTDIR/doc_list.txt"

cd /tmp
tar czf "$OUTFILE" -C /tmp "esmind-export-${TS}" 2>/dev/null
cd - > /dev/null

echo "打包完成: $OUTFILE"
echo "大小: $(ls -lh "$OUTFILE" | awk '{print $5}')"

# 清理临时目录
rm -rf "$OUTDIR"

echo ""
echo "========================================================"
echo "✅ 导出完成！"
echo "========================================================"
echo ""
echo "请将以下文件传回测试服务器（192.168.8.232）："
echo "  $OUTFILE"
echo ""
echo "传回后执行："
echo "  tar xzf $(basename $OUTFILE)"
echo "  python3 scripts/import-raw.py --input /tmp/esmind-export-${TS} --index $INDEX"