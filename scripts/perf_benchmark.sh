#!/bin/bash
# ESMind v2 性能基准测试
# 测量各查询类型的耗时分布（min/avg/p95/max）

set -euo pipefail

BASE_URL="${ESMIND_URL:-http://localhost:8080}"

QUERIES=(
    # SIMPLE
    "病案首页"
    "门诊就诊记录"
    "糖尿病患者"
    "化验"
    "近30天的脑梗死患者"
    # COMPLEX
    "糖尿病患者中年龄大于60岁的"
    "门诊就诊记录按月分布"
    "各科室住院患者统计"
    # PIVOT
    "发烧的患者白细胞"
    "高血压的患者就诊记录"
    # TREND_COMPARE
    "上月vs本月门诊量"
    "今年对比去年就诊人数"
    # MULTI_CHAIN
    "糖尿病患者的并且高血压患者"
)

RUNS=3

echo "══════════════════════════════════════════════════"
echo "ESMind v2 性能基准测试"
echo "Server: ${BASE_URL}"
echo "Runs per query: ${RUNS}"
echo "Date:   $(date '+%Y-%m-%d %H:%M:%S')"
echo "══════════════════════════════════════════════════"
echo ""

run_query() {
    local question="$1"
    local response elapsed_ms
    response=$(curl -s --max-time 120 \
        -X POST "${BASE_URL}/api/chat" \
        -H "Content-Type: application/json" \
        -d "{\"question\":\"${question}\"}")
    elapsed_ms=$(echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('elapsed_ms', 'FAIL'))" 2>/dev/null || echo "PARSE_ERR")
    echo "$elapsed_ms"
}

echo "| Query | Run1(ms) | Run2(ms) | Run3(ms) | Decision |"
echo "|-------|----------|----------|----------|----------|"

declare -a ALL_TIMES

for query in "${QUERIES[@]}"; do
    times=()
    decision=""
    for ((r=1; r<=RUNS; r++)); do
        t=$(run_query "$query")
        if [ "$r" = "1" ]; then
            # get decision from first run
            decision=$(curl -s --max-time 120 \
                -X POST "${BASE_URL}/api/chat" \
                -H "Content-Type: application/json" \
                -d "{\"question\":\"${query}\"}" | \
                python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('decision','?'))" 2>/dev/null || echo "?")
            t=$(run_query "$query")
        fi
        if [ "$t" != "FAIL" ] && [ "$t" != "PARSE_ERR" ]; then
            times+=("$t")
            ALL_TIMES+=("$t")
        fi
    done
    printf "| %-40s | %-8s | %-8s | %-8s | %-8s |\n" \
        "$(echo "$query" | head -c 40)" \
        "${times[0]:-ERR}" \
        "${times[1]:-ERR}" \
        "${times[2]:-ERR}" \
        "$decision"
done

echo ""
echo "══════════════════════════════════════════════════"
echo "汇总统计"

if [ ${#ALL_TIMES[@]} -gt 0 ]; then
    # sort
    IFS=$'\n' SORTED=($(sort -n <<<"${ALL_TIMES[*]}")); unset IFS
    COUNT=${#SORTED[@]}
    SUM=0
    for t in "${SORTED[@]}"; do SUM=$((SUM + t)); done
    AVG=$((SUM / COUNT))
    MIN=${SORTED[0]}
    MAX=${SORTED[$((COUNT - 1))]}
    P95_INDEX=$((COUNT * 95 / 100))
    [ "$P95_INDEX" -ge "$COUNT" ] && P95_INDEX=$((COUNT - 1))
    P95=${SORTED[$P95_INDEX]}

    echo "  总请求: ${COUNT}"
    echo "  最小值: ${MIN}ms"
    echo "  平均值: ${AVG}ms"
    echo "  P95:    ${P95}ms"
    echo "  最大值: ${MAX}ms"
fi

echo "══════════════════════════════════════════════════"
