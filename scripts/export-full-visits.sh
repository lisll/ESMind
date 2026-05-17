#!/bin/bash
# =============================================================================
# ESMind — 从真实ES按就诊次完整导出（纯 bash/curl，不需要 Python）
# 在 10.2.4.146 上直接运行
#
# 用法：
#   chmod +x export-full-visits.sh
#   ./export-full-visits.sh [max_docs] [batch_size]
#
# 默认导出 5 条，可以按需修改下面的配置
# =============================================================================

# ---------- 配置 ----------
ES="http://127.0.0.1:9230"
INDEX="xnk20231220_clinical_inhistory_0122113057"
MAX_DOCS="${1:-5}"        # 导出条数，默认 5
BATCH_SIZE="${2:-2}"      # 并发批大小（用 & 后台）
OUTPUT="esmind-visits.ndjson"

echo "=== ESMind 就诊次导出工具 ==="
echo "ES:     $ES"
echo "索引:   $INDEX"
echo "条数:   $MAX_DOCS"
echo "输出:   $OUTPUT"
echo ""

# ---------- Step 1: Scroll 获取所有文档 ID ----------
echo "[1/3] 获取文档 ID（_source=false 绕过 shard 0 损坏）..."
TMPDIR=$(mktemp -d)
ID_FILE="$TMPDIR/doc_ids.txt"
SCROLL_FILE="$TMPDIR/scroll.json"

# 初始化 scroll
SCROLL_BODY='{"size":500,"_source":false,"query":{"match_all":{}},"sort":["_doc"],"stored_fields":["_none_"]}'
SCROLL_TIME="5m"

rm -f "$ID_FILE"
touch "$ID_FILE"

# 第一次请求
curl -s -X POST "$ES/$INDEX/_search?scroll=$SCROLL_TIME" \
  -H 'Content-Type: application/json' \
  -d "$SCROLL_BODY" > "$SCROLL_FILE" 2>/dev/null

SCROLL_ID=$(python3 -c "import json; d=json.load(open('$SCROLL_FILE')); print(d.get('_scroll_id',''))" 2>/dev/null || \
            grep -o '"_scroll_id":"[^"]*"' "$SCROLL_FILE" | head -1 | sed 's/"_scroll_id":"//;s/"//')

python3 -c "
import json,sys
d=json.load(open('$SCROLL_FILE'))
hits=d.get('hits',{}).get('hits',[])
total=d.get('hits',{}).get('total',0)
print(f'索引总文档数: {total}')
with open('$ID_FILE','a') as f:
    for h in hits:
        f.write(h['_id']+'\n')
print(f'已获取 {len(hits)} 个 ID')
" 2>/dev/null || {
  # 如果没 python3，用 grep +
  echo "⚠️ 没有 python3，用 grep 提取..."
  grep -o '"_id":"[^"]*"' "$SCROLL_FILE" | cut -d'"' -f4 >> "$ID_FILE"
  echo "已获取 $(wc -l < $ID_FILE) 个 ID"
}

COUNT=$(wc -l < "$ID_FILE")

# 继续 scroll
while [ "$COUNT" -lt "$MAX_DOCS" ] && [ -n "$SCROLL_ID" ]; do
  curl -s -X POST "$ES/_search/scroll" \
    -H 'Content-Type: application/json' \
    -d "{\"scroll\":\"${SCROLL_TIME}\",\"scroll_id\":\"${SCROLL_ID}\"}" > "$SCROLL_FILE" 2>/dev/null

  # 提取新的 scroll_id 和 hits
  NEW_HITS=$(python3 -c "
import json
d=json.load(open('$SCROLL_FILE'))
hits=d.get('hits',{}).get('hits',[])
with open('$ID_FILE','a') as f:
    for h in hits:
        f.write(h['_id']+'\n')
sid=d.get('_scroll_id','')
print(f'got {len(hits)} hits, scroll_id={sid[:30]}...')
" 2>/dev/null)
  
  SCROLL_ID=$(echo "$NEW_HITS" | grep -o 'scroll_id=[^ ]*' | cut -d= -f2)
  
  COUNT=$(wc -l < "$ID_FILE")
  echo "已获取 $COUNT 个 ID..."
  
  # 检查是否无新数据
  PREV_COUNT=$COUNT
  if [ "$COUNT" -eq "$PREV_COUNT" ]; then
      break
  fi
  PREV_COUNT=$COUNT
done

# 清理 scroll
curl -s -X DELETE "$ES/_search/scroll" \
  -H 'Content-Type: application/json' \
  -d "{\"scroll_id\":\"${SCROLL_ID}\"}" > /dev/null 2>&1

# 截取前 MAX_DOCS 条
head -n "$MAX_DOCS" "$ID_FILE" > "${ID_FILE}.trimmed"
mv "${ID_FILE}.trimmed" "$ID_FILE"
COUNT=$(wc -l < "$ID_FILE")
echo "共 $COUNT 个文档 ID"
echo ""

# ---------- Step 2: 逐条导出完整文档 ----------
echo "[2/3] 导出 $COUNT 条文档..."

> "$OUTPUT"  # 清空输出文件
SUCCESS=0
FAIL=0
BATCH_NUM=0
BATCH_FILES=""

process_doc() {
  local DOC_ID="$1"
  local OUT="$2"
  local TRACE="$3"
  
  # 构造嵌套查询：用常见的 7 个 nested 表
  # 太长，用文件传
  cat > "$OUT.query.json" << 'QUERYEOF'
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
        {"nested": {"path": "shouyeshushi", "query": {"match_all": {}}, "inner_hits": {"name": "shouyeshushi", "size": 5000, "_source": true}}}
      ],
      "minimum_should_match": 1
    }
  }
}
QUERYEOF

  # 替换文档 ID
  sed "s/__ID__/$(echo "$DOC_ID" | sed 's/[\/&]/\\&/g')/g" "$OUT.query.json" > "${OUT}.query_subst.json"
  
  # 发送请求
  curl -s -X POST "$ES/$INDEX/_search" \
    -H 'Content-Type: application/json' \
    -d @"${OUT}.query_subst.json" > "$OUT" 2>/dev/null
  
  # 检查结果
  if grep -q '"hits":{"hits":\[' "$OUT" 2>/dev/null; then
    # 成功，提取精简的单行 JSON 追加到 ndjson
    python3 -c "
import json,sys
d=json.load(open('$OUT'))
hits=d.get('hits',{}).get('hits',[])
if not hits:
    sys.exit(1)
hit=hits[0]
src=hit.get('_source',{})
ih=hit.get('inner_hits',{})

# 组装文档
doc={}
if src and isinstance(src,dict) and len(src)>0:
    doc['patient']=src.get('patient',{})
    for k in ('binganshouye','ruyuanjilu'):
        if k in src:
            doc[k]=src[k]

# 提取 nested 表
for path in ['shouyezhenduan','yizhu','jianyanbaogaofu','jianchabaogaofu','binglizhenduan','shouyeshoushu','shouyeshushi']:
    ih_hits=ih.get(path,{}).get('hits',{}).get('hits',[])
    if ih_hits:
        items=[]
        for ih_hit in ih_hits:
            ih_src=ih_hit.get('_source',{})
            if ih_src:
                cleaned={}
                for k,v in ih_src.items():
                    if k.startswith(path+'.'):
                        cleaned[k[len(path)+1:]]=v
                    else:
                        cleaned[k]=v
                items.append(cleaned)
        if items:
            doc[path]=items

if not doc:
    sys.exit(2)

pid=doc.get('patient',{}).get('patient_id','?')
tables=list(doc.keys())
print(f'OK\t{pid}\t{tables}', file=sys.stderr)
print(json.dumps(doc, ensure_ascii=False))
" >> "$TRACE" 2>> "${TRACE}.err"
    
    if [ $? -eq 0 ]; then
      # 提取最后一行（那个 print(json.dumps)）追加到 OUTPUT
      tail -1 "$TRACE" >> "$OUTPUT"
      return 0
    else
      return 2
    fi
  else
    return 1
  fi
}

BATCH_COUNTER=0
while IFS= read -r DOC_ID; do
  [ -z "$DOC_ID" ] && continue
  
  BATCH_NUM=$((BATCH_NUM + 1))
  BATCH_COUNTER=$((BATCH_COUNTER + 1))
  TRACE_FILE="$TMPDIR/result_${BATCH_NUM}.txt"
  ERR_FILE="$TMPDIR/result_${BATCH_NUM}.err"
  OUT_FILE="$TMPDIR/result_${BATCH_NUM}.json"
  > "$TRACE_FILE"
  > "$ERR_FILE"
  
  SHORT_ID=$(echo "$DOC_ID" | head -c 50)
  printf "  [%d/%d] %s... " "$BATCH_NUM" "$COUNT" "$SHORT_ID"
  
  process_doc "$DOC_ID" "$OUT_FILE" "$TRACE_FILE" &
  PID=$!
  
  # 如果是批量模式，收集 PID
  if [ $((BATCH_COUNTER % BATCH_SIZE)) -eq 0 ] || [ "$BATCH_NUM" -eq "$COUNT" ]; then
    wait
    # 逐个检查结果
    for i in $(seq $((BATCH_NUM - BATCH_COUNTER + 1)) $BATCH_NUM); do
      TR="$TMPDIR/result_${i}.txt"
      ER="$TMPDIR/result_${i}.err"
      if [ -s "$TR" ]; then
        STATUS=$(head -1 "$TR" 2>/dev/null | cut -f1)
        if [ "$STATUS" = "OK" ]; then
          echo "✅"
          SUCCESS=$((SUCCESS + 1))
        else
          echo "⚠️ 部分数据"
          SUCCESS=$((SUCCESS + 1))
        fi
      else
        ERRTEXT=$(head -1 "$ER" 2>/dev/null | cut -c1-100)
        echo "❌ ${ERRTEXT:-空结果}"
        FAIL=$((FAIL + 1))
      fi
    done
    BATCH_COUNTER=0
  fi
done < "$ID_FILE"

# 等待最后一批
wait

echo ""
echo "结果: ✅ $SUCCESS 成功 | ❌ $FAIL 失败"

# ---------- Step 3: 统计 ----------
echo ""
echo "[3/3] 统计"
if [ "$SUCCESS" -gt 0 ]; then
  echo "导出文档数: $SUCCESS"
  echo "输出文件:   $OUTPUT"
  echo "文件大小:   $(wc -c < "$OUTPUT") 字节"
  
  # 各表统计
  python3 -c "
import json
tables={}
with open('$OUTPUT') as f:
    for line in f:
        doc=json.loads(line)
        for k,v in doc.items():
            if k not in tables:
                tables[k]={'docs':0,'items':0}
            tables[k]['docs']+=1
            if isinstance(v,list):
                tables[k]['items']+=len(v)
            else:
                tables[k]['items']+=1
print('业务表覆盖:')
for t,s in sorted(tables.items()):
    print(f'  {t}: {s[\"docs\"]}/{SUCCESS} 文档, {s[\"items\"]} 条')
" 2>/dev/null || echo "⚠️ 无法统计（python3 不可用）"
fi

# ---------- 清理 ----------
rm -rf "$TMPDIR"

echo ""
echo "✅ 导出完成！"
echo "下一步: 将 $OUTPUT 传回测试服务器后执行"
echo "  python3 scripts/import-data.py --index $INDEX --input $OUTPUT"