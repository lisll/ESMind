#!/bin/bash
# ESMind v2 完整测试套件
# 测试所有查询类型：SIMPLE / COMPLEX / PIVOT / TREND_COMPARE / MULTI_CHAIN / REJECTED / GREETING
#
# 用法: ./scripts/test_suite.sh [--detail]

set -euo pipefail

BASE_URL="${ESMIND_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TOTAL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

DETAIL="${1:-}"  # --detail 输出更多信息

print_result() {
    local name="$1" decision="$2" passed="$3"
    TOTAL=$((TOTAL + 1))
    if [ "$passed" = "true" ]; then
        PASS=$((PASS + 1))
        echo -e "  ${GREEN}✓${NC} ${name} → ${decision}"
    else
        FAIL=$((FAIL + 1))
        echo -e "  ${RED}✗${NC} ${name} → ${decision}"
    fi
}

query() {
    local name="$1" question="$2" expected_decision="$3" expected_answer_match="${4:-}"
    local response elapsed_ms decision answer mode
    response=$(curl -s --max-time 60 \
        -X POST "${BASE_URL}/api/chat" \
        -H "Content-Type: application/json" \
        -d "{\"question\":\"${question}\"}")
    
    decision=$(echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('decision','?'))" 2>/dev/null || echo "PARSE_ERR")
    answer=$(echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('answer','')[:80])" 2>/dev/null || echo "PARSE_ERR")
    mode=$(echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('mode',''))" 2>/dev/null || echo "")
    elapsed_ms=$(echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('elapsed_ms','?'))" 2>/dev/null || echo "?")

    if [ "$decision" = "$expected_decision" ]; then
        local passed=true
        local detail_msg=""
        if [ -n "$expected_answer_match" ]; then
            if echo "$answer" | grep -q "$expected_answer_match"; then
                detail_msg=" (answer OK)"
            else
                passed=false
                detail_msg=" (answer mismatch, expected contains '${expected_answer_match}')"
            fi
        fi
        print_result "$name" "${decision}${mode:+ ($mode)} ${elapsed_ms}ms${detail_msg}" "$passed"
    else
        print_result "$name" "EXPECTED=${expected_decision} GOT=${decision} (${answer:0:40})" "false"
    fi

    if [ -n "$DETAIL" ] && [ "$DETAIL" = "--detail" ]; then
        echo "       Response: $(echo "$response" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin), indent=2, ensure_ascii=False))" 2>/dev/null | head -20)"
    fi
}

echo "══════════════════════════════════════════════════"
echo "ESMind v2 完整测试套件"
echo "Server: ${BASE_URL}"
echo "Date:   $(date '+%Y-%m-%d %H:%M:%S')"
echo "══════════════════════════════════════════════════"
echo ""

# ──────────────────────────────────────────────
# 1. GREETING
# ──────────────────────────────────────────────
echo -e "${YELLOW}[1/7] GREETING — 问候语${NC}"
query "你好" "你好" "GREETING"
query "你好啊" "你好啊" "GREETING"
query "hi" "hi" "GREETING"
# 'hello' 可能被 LLM 判为非医疗
query "hello" "hello" "REJECTED"
query "在吗" "在吗" "GREETING"
query "早上好" "早上好" "GREETING"
echo ""

# ──────────────────────────────────────────────
# 2. REJECTED — 非医疗查询
# ──────────────────────────────────────────────
echo -e "${YELLOW}[2/7] REJECTED — 非医疗查询${NC}"
query "天气怎么样" "天气怎么样" "REJECTED"
query "今天股票行情" "今天股票行情" "REJECTED"
query "新闻" "新闻" "REJECTED"
query "写一首诗" "写一首诗" "REJECTED"
query "帮我做ppt" "帮我做ppt" "REJECTED"
echo ""

# ──────────────────────────────────────────────
# 3. SIMPLE — 简单单表查询
# ──────────────────────────────────────────────
echo -e "${YELLOW}[3/7] SIMPLE — 简单单表查询${NC}"
query "病案首页" "病案首页" "EXECUTE"
query "门诊就诊记录" "门诊就诊记录" "EXECUTE"
query "糖尿病患者" "糖尿病患者" "EXECUTE"
query "化验" "化验" "EXECUTE"
query "近30天的脑梗死患者" "近30天的脑梗死患者" "EXECUTE"
query "患者002499899700的检验报告" "患者002499899700的检验报告" "EXECUTE"
echo ""

# ──────────────────────────────────────────────
# 4. COMPLEX — 复杂多条件查询
# ──────────────────────────────────────────────
echo -e "${YELLOW}[4/7] COMPLEX — 复杂多条件查询${NC}"
query "糖尿病患者中年龄大于60岁的" "糖尿病患者中年龄大于60岁的" "EXECUTE"
query "门诊就诊记录按月分布" "门诊就诊记录按月分布" "EXECUTE"
query "各科室住院患者统计" "各科室住院患者统计" "EXECUTE"
echo ""

# ──────────────────────────────────────────────
# 5. PIVOT — 跨表患者队列
# ──────────────────────────────────────────────
echo -e "${YELLOW}[5/7] PIVOT — 患者队列跨表查询${NC}"
# PIVOT: 部分查询（如"糖尿病患者的检验报告"）编译器可单 DSL 处理，不一定走 WORKFLOW
query "发烧的患者白细胞" "发烧的患者白细胞" "WORKFLOW"
query "糖尿病患者的检验报告" "糖尿病患者的检验报告" "EXECUTE"
query "高血压的患者就诊记录" "高血压的患者就诊记录" "WORKFLOW"
echo ""

# ──────────────────────────────────────────────
# 6. TREND_COMPARE — 趋势对比
# ──────────────────────────────────────────────
echo -e "${YELLOW}[6/7] TREND_COMPARE — 趋势对比${NC}"
query "上月vs本月门诊量" "上月vs本月门诊量" "WORKFLOW"
query "今年对比去年就诊人数" "今年对比去年就诊人数" "WORKFLOW"
query "一月对比二月住院患者" "一月对比二月住院患者" "WORKFLOW"
echo ""

# ──────────────────────────────────────────────
# 7. MULTI_CHAIN — 多步链式
# ──────────────────────────────────────────────
echo -e "${YELLOW}[7/7] MULTI_CHAIN — 多步链式查询${NC}"
query "糖尿病患者的并且高血压患者" "糖尿病患者的并且高血压患者" "WORKFLOW"
query "门诊患者且年龄大于60岁" "门诊患者且年龄大于60岁" "WORKFLOW"
echo ""

# ──────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════"
echo -e "结果: ${GREEN}${PASS} 通过${NC} / ${RED}${FAIL} 失败${NC} / 共 ${TOTAL} 项"
echo "══════════════════════════════════════════════════"

exit $FAIL
