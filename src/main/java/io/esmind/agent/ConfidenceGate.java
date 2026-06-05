package io.esmind.agent;

import io.esmind.semantic.SemanticIR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * ConfidenceGate — 查询置信度评估与域检测。
 *
 * 纯 LLM domain 分类，不依赖任何关键词列表。
 * 职责：决策（EXECUTE/REJECT/GREETING/CANDIDATES），不负责计时、不负责空白检查。
 */
public class ConfidenceGate {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceGate.class);

    public enum Decision {
        REJECT,
        GREETING,
        EXECUTE,
        CANDIDATES
    }

    public static class Result {
        private final Decision decision;
        private final double confidence;
        private final String message;
        private final List<String> candidates;
        private final SemanticIR parsedIr;
        private long elapsedMs; // 决策耗时，由调用者设置

        public Result(Decision decision, double confidence, String message,
                      List<String> candidates, SemanticIR parsedIr) {
            this.decision = decision;
            this.confidence = confidence;
            this.message = message;
            this.candidates = candidates;
            this.parsedIr = parsedIr;
            this.elapsedMs = 0;
        }

        public Decision getDecision() { return decision; }
        public double getConfidence() { return confidence; }
        public String getMessage() { return message; }
        public List<String> getCandidates() { return candidates; }
        public SemanticIR getParsedIr() { return parsedIr; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long ms) { this.elapsedMs = ms; }

        public boolean isRejected() { return decision == Decision.REJECT; }
        public boolean isGreeting() { return decision == Decision.GREETING; }
        public boolean needsCandidates() { return decision == Decision.CANDIDATES; }
        public boolean isExecutable() { return decision == Decision.EXECUTE; }
    }

    /**
     * 后评估 — 基于 LLM 输出的 SemanticIR.domain 做决策。
     *
     * <p>这是真正的领域判断入口。LLM 在解析时已经分类了 domain：
     * <ul>
     *   <li>domain=medical → 正常执行（检查实体是否有效）</li>
     *   <li>domain=greeting → 友好回应</li>
     *   <li>domain=non_medical → 拒绝</li>
     * </ul>
     *
     * @param query 原始查询
     * @param ir    LLM 解析结果
     * @return 决策结果
     */
    public Result evaluate(String query, SemanticIR ir) {
        long start = System.currentTimeMillis();
        try {
            return doEvaluate(query, ir);
        } finally {
            // elapsedMs 由调用者根据外部时序计算，此处不设置
        }
    }

    private Result doEvaluate(String query, SemanticIR ir) {
        if (query == null || query.isBlank()) {
            return new Result(Decision.CANDIDATES, 0.0,
                    "请输入查询内容。", Collections.emptyList(), null);
        }
        if (ir == null) {
            return new Result(Decision.CANDIDATES, 0.0,
                    "无法解析您的查询。", Collections.emptyList(), null);
        }

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

        log.info("ConfidenceGate: POST EXECUTE (domain={}, intent={}, entities={})",
                domain, ir.getIntent(), entities.size());
        return new Result(Decision.EXECUTE, 0.9, null, Collections.emptyList(), ir);
    }

    private List<String> getDefaultCandidates() {
        return Arrays.asList(
                "查询患者病历信息",
                "统计诊断分布",
                "查看检验报告",
                "查询门诊就诊记录"
        );
    }
}
