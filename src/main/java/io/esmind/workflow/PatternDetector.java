package io.esmind.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * PatternDetector — 从 NL 查询中检测工作流模式。
 * <p>
 * 支持三种模式：
 * <ol>
 *   <li>PIVOT: "X的患者Y" → 先查出X患者再查Y</li>
 *   <li>MULTI_CHAIN: 多步条件链 → 逐步收窄结果集</li>
 *   <li>TREND_COMPARE: "vs/对比" → 两个时间范围对比</li>
 * </ol>
 */
public class PatternDetector {

    private static final Logger log = LoggerFactory.getLogger(PatternDetector.class);

    public enum Pattern {
        /** 患者队列→跨表 pivot 查询 */
        PIVOT,
        /** 多步条件链（逐步收窄） */
        MULTI_CHAIN,
        /** 趋势对比 */
        TREND_COMPARE,
        /** 未检测到工作流模式 */
        NONE
    }

    /**
     * 检测结果：模式类型 + 拆分段。
     */
    public static class DetectionResult {
        private final Pattern pattern;
        private final String description;
        /** PIVOT: [cohortQuery, detailQuery] */
        /** MULTI_CHAIN: [step1, step2, step3, ...] */
        /** TREND_COMPARE: [baseQuery, timeRangeA, timeRangeB] */
        private final List<String> segments;
        /** TREND_COMPARE: [aggTypeA, aggTypeB] */
        private final List<String> metadata;

        public DetectionResult(Pattern pattern, String description,
                               List<String> segments) {
            this(pattern, description, segments, Collections.emptyList());
        }

        public DetectionResult(Pattern pattern, String description,
                               List<String> segments, List<String> metadata) {
            this.pattern = pattern;
            this.description = description;
            this.segments = segments;
            this.metadata = metadata;
        }

        public Pattern getPattern() { return pattern; }
        public String getDescription() { return description; }
        public List<String> getSegments() { return segments; }
        public String getSegment(int i) { return i >= 0 && i < segments.size() ? segments.get(i) : null; }
        public int segmentCount() { return segments.size(); }
        public List<String> getMetadata() { return metadata; }
        public boolean isFound() { return pattern != Pattern.NONE; }
    }

    // ========================================================================
    // PIVOT 模式关键词
    // ========================================================================

    /** PIVOT 连接词（按优先级） */
    private static final List<String> PIVOT_CONNECTORS = List.of(
            "的患者"   // "发烧的患者白细胞" → ["发烧", "白细胞"]
    );

    // ========================================================================
    // TREND_COMPARE 关键词
    // ========================================================================

    private static final List<String> COMPARE_KEYWORDS = List.of(
            "vs", "VS", "Vs", "对比", "versus", "versu",
            "比较", "差异", "变化", "变化趋势",
            "与去年同期", "环比", "同比"
    );

    // 时间范围标记词（用于拆分趋势对比的两端）
    private static final List<String> TIME_MARKERS = List.of(
            "近", "前", "上", "本", "今", "去", "去年", "今年",
            "本月", "上月", "本月", "上周", "本周", "昨日", "今日"
    );

    // ========================================================================
    // Main Detection
    // ========================================================================

    /**
     * 检测 NL 查询的工作流模式。
     */
    public DetectionResult detect(String nlQuery) {
        if (nlQuery == null || nlQuery.isBlank()) {
            return new DetectionResult(Pattern.NONE, "empty query", Collections.emptyList());
        }

        // 1. TREND_COMPARE: 包含 vs/对比 关键词
        DetectionResult compareResult = detectTrendCompare(nlQuery);
        if (compareResult != null) return compareResult;

        // 2. PIVOT: "X的患者Y"
        DetectionResult pivotResult = detectPivot(nlQuery);
        if (pivotResult != null) return pivotResult;

        // 3. MULTI_CHAIN: 多步条件链（由 LLM SemanticIR 判定）
        // 暂不实现启发式检测，留给 LLM 输出指示

        return new DetectionResult(Pattern.NONE, "no workflow pattern detected",
                Collections.emptyList());
    }

    /**
     * 检测 PIVOT 模式："X的患者Y"
     */
    DetectionResult detectPivot(String nlQuery) {
        for (String connector : PIVOT_CONNECTORS) {
            String[] parts = nlQuery.split(connector, 2);
            if (parts.length == 2) {
                String left = parts[0].trim();
                String right = parts[1].trim();
                // 过滤掉两边为空或只有 "的" 的情况
                if (!left.isEmpty() && !right.isEmpty()
                        && !"的".equals(left) && !"的".equals(right)) {
                    log.info("PatternDetector: PIVOT '{}' → '{}'", left, right);
                    return new DetectionResult(Pattern.PIVOT,
                            "患者队列 pivot: " + left + " → " + right,
                            List.of(left, right));
                }
            }
        }
        return null;
    }

    /**
     * 检测 TREND_COMPARE 模式："X vs Y" 或 "X对比Y"
     */
    DetectionResult detectTrendCompare(String nlQuery) {
        // 检查是否包含对比关键词
        boolean hasCompareKeyword = false;
        String compareKeywordUsed = null;
        for (String kw : COMPARE_KEYWORDS) {
            if (nlQuery.contains(kw)) {
                hasCompareKeyword = true;
                compareKeywordUsed = kw;
                break;
            }
        }
        if (!hasCompareKeyword) return null;

        // 尝试按对比关键词拆分
        // "上月vs本月门诊就诊人数" → ["上月", "本月门诊就诊人数"]
        // "今年对比去年门诊量" → ["今年", "去年门诊量"]
        for (String kw : COMPARE_KEYWORDS) {
            if (!nlQuery.contains(kw)) continue;
            // 短关键词（如 "vs"）：只有被字母边界包围时才排除（避免 "service" 误匹配）
            if (kw.length() <= 2) {
                int idx = nlQuery.indexOf(kw);
                char prev = idx > 0 ? nlQuery.charAt(idx - 1) : ' ';
                char next = idx + kw.length() < nlQuery.length() ? nlQuery.charAt(idx + kw.length()) : ' ';
                boolean prevIsAsciiLetter = (prev >= 'A' && prev <= 'Z') || (prev >= 'a' && prev <= 'z');
                boolean nextIsAsciiLetter = (next >= 'A' && next <= 'Z') || (next >= 'a' && next <= 'z');
                if (prevIsAsciiLetter && nextIsAsciiLetter) continue; // 被英文单词包围 → 不是分隔符
            }

            String[] parts = nlQuery.split(kw, 2);
            if (parts.length == 2) {
                String left = parts[0].trim();
                String right = parts[1].trim();
                if (!left.isEmpty() && !right.isEmpty()) {
                    log.info("PatternDetector: TREND_COMPARE '{}' vs '{}'", left, right);
                    return new DetectionResult(Pattern.TREND_COMPARE,
                            "趋势对比: " + left + " vs " + right,
                            List.of(left, right),
                            List.of(compareKeywordUsed));
                }
            }
        }

        // 复杂模式：未拆分成功但仍含对比词 → 回退 NONE
        log.info("PatternDetector: compare keyword '{}' found but cannot split", compareKeywordUsed);
        return null;
    }

    /**
     * 检查查询是否为 TREND_COMPARE（用于 ChatController 前置判断）。
     */
    public boolean isCompareQuery(String nlQuery) {
        DetectionResult r = detectTrendCompare(nlQuery);
        return r != null;
    }

    /**
     * 检查查询是否为 PIVOT（用于 ChatController 前置判断）。
     */
    public boolean isPivotQuery(String nlQuery) {
        DetectionResult r = detectPivot(nlQuery);
        return r != null;
    }
}
