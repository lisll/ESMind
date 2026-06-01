package io.esmind.agent;

import io.esmind.semantic.SemanticIR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * ConfidenceGate — 查询置信度评估与域检测。
 * <p>
 * 核心设计：不依赖任何关键词列表，完全基于 LLM 的 domain 分类。
 * <p>
 * LLM 在解析查询时输出 domain 字段：
 * <ul>
 *   <li>medical → 医疗数据查询，走正常管线</li>
 *   <li>greeting → 问候语，友好回应</li>
 *   <li>non_medical → 拒绝，不走 ES</li>
 * </ul>
 * <p>
 * 不需要维护任何关键词列表——LLM 天然理解什么是医疗相关、什么是问候语。
 */
public class ConfidenceGate {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceGate.class);

    // ========================================================================
    // 决策结果
    // ========================================================================

    public enum Decision {
        REJECT,      // 无关查询，直接拒绝
        GREETING,    // 问候语，友好回应
        EXECUTE,     // 医疗查询，直接执行
        CANDIDATES   // 解析失败，提示用户
    }

    public static class Result {
        private final Decision decision;
        private final double confidence;
        private final String message;
        private final List<String> candidates;
        private final SemanticIR parsedIr;

        public Result(Decision decision, double confidence, String message,
                      List<String> candidates, SemanticIR parsedIr) {
            this.decision = decision;
            this.confidence = confidence;
            this.message = message;
            this.candidates = candidates;
            this.parsedIr = parsedIr;
        }

        public Decision getDecision() { return decision; }
        public double getConfidence() { return confidence; }
        public String getMessage() { return message; }
        public List<String> getCandidates() { return candidates; }
        public SemanticIR getParsedIr() { return parsedIr; }

        public boolean isRejected() { return decision == Decision.REJECT; }
        public boolean isGreeting() { return decision == Decision.GREETING; }
        public boolean needsCandidates() { return decision == Decision.CANDIDATES; }
        public boolean isExecutable() { return decision == Decision.EXECUTE; }
    }

    // ========================================================================
    // 评估方法
    // ========================================================================

    /**
     * 预评估 — 基于 LLM 的 domain 分类。
     * <p>
     * 不再需要预评估——LLM 解析时直接输出 domain 字段。
     * 这个方法现在只做基本的空白检查，真正的领域判断交给 LLM。
     */
    public Result evaluatePre(String query) {
        if (query == null || query.isBlank()) {
            return new Result(Decision.CANDIDATES, 0.0,
                    "请输入查询内容。", Collections.emptyList(), null);
        }

        // 不再做任何关键词匹配。LLM 的 domain 输出是唯一判断依据。
        return new Result(Decision.EXECUTE, 0.5, null,
                Collections.emptyList(), null);
    }

    /**
     * 后评估 — 基于 LLM 输出的 SemanticIR.domain 做决策。
     * <p>
     * 这是真正的领域判断入口。LLM 在解析时已经分类了 domain：
     * <ul>
     *   <li>domain=medical → 正常执行（检查实体是否有效）</li>
     *   <li>domain=greeting → 友好回应</li>
     *   <li>domain=non_medical → 拒绝</li>
     * </ul>
     */
    public Result evaluatePost(String query, SemanticIR ir) {
        if (ir == null) {
            return new Result(Decision.CANDIDATES, 0.0,
                    "无法解析您的查询。", Collections.emptyList(), null);
        }

        // === 1. 基于 LLM domain 分类做决策 ===
        String domain = ir.getDomain();

        if ("greeting".equals(domain)) {
            log.info("ConfidenceGate: GREETING (LLM domain=greeting): {}", query);
            return new Result(Decision.GREETING, 1.0,
                    "您好！我是医疗数据查询助手。请描述您要查询的内容，例如：患者信息、诊断记录、检验报告、门诊就诊记录等。",
                    Collections.emptyList(), ir);
        }

        if ("non_medical".equals(domain)) {
            log.info("ConfidenceGate: REJECTED (LLM domain=non_medical): {}", query);
            return new Result(Decision.REJECT, 0.0,
                    "抱歉，我只能回答医疗数据相关的问题。请描述您要查询的病历数据内容。",
                    Collections.emptyList(), ir);
        }

        // domain=medical（或未设置时的默认行为）
        if (ir.getIntent() == null || ir.getIntent().isBlank()) {
            return new Result(Decision.CANDIDATES, 0.2,
                    "我没能理解您的查询意图。请重新描述您要查询的病历数据。",
                    getDefaultCandidates(), ir);
        }

        List<SemanticIR.Entity> entities = ir.getEntities();
        if (entities == null || entities.isEmpty()) {
            return new Result(Decision.CANDIDATES, 0.3,
                    "请指定要查询的内容，例如：患者、诊断名称、检验项目等。",
                    getDefaultCandidates(), ir);
        }

        // LLM 成功解析 → 直接执行
        log.info("ConfidenceGate: POST EXECUTE (domain={}, intent={}, entities={})",
                domain, ir.getIntent(), entities.size());
        return new Result(Decision.EXECUTE, 0.9, null, Collections.emptyList(), ir);
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    private List<String> getDefaultCandidates() {
        return Arrays.asList(
                "查询患者病历信息",
                "统计诊断分布",
                "查看检验报告",
                "查询门诊就诊记录"
        );
    }
}
