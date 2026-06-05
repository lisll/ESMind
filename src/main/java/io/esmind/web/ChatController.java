package io.esmind.web;

import io.esmind.agent.ConfidenceGate;
import io.esmind.agent.EsMindCompiler;
import io.esmind.agent.MedicalQueryAgent;
import io.esmind.compiler.QueryPlanner;
import io.esmind.semantic.SemanticIR;
import io.esmind.workflow.PatternDetector;
import io.esmind.workflow.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final EsMindCompiler compiler;
    private final ConfidenceGate confidenceGate;
    private final MedicalQueryAgent agent;
    private final WorkflowEngine workflowEngine;
    private final PatternDetector patternDetector;
    private final QueryPlanner queryPlanner;

    public ChatController(EsMindCompiler esMindCompiler,
                          MedicalQueryAgent medicalQueryAgent,
                          WorkflowEngine workflowEngine,
                          QueryPlanner queryPlanner) {
        this.compiler = esMindCompiler;
        this.confidenceGate = new ConfidenceGate();
        this.agent = medicalQueryAgent;
        this.workflowEngine = workflowEngine;
        this.patternDetector = new PatternDetector();
        this.queryPlanner = queryPlanner;
        log.info("ChatController initialized with v2 Compiler + ConfidenceGate + WorkflowEngine + QueryPlanner: index={}",
                esMindCompiler != null ? "configured" : "?");
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    // ========================================================================
    // /api/chat — 标准查询接口
    // ========================================================================

    @PostMapping(value = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        long startTime = System.currentTimeMillis();
        log.info("[chat] User question: {}", question);

        // === Phase 1: Workflow 检测（TREND_COMPARE / PIVOT / MULTI_CHAIN）===
        Map<String, Object> wfResult = tryWorkflowRoutes(question, startTime);
        if (wfResult != null) return ResponseEntity.ok(wfResult);

        // === Phase 2: Compile（LLM parse + DSL + ES query）===
        EsMindCompiler.QueryResponse response = compiler.compile(question);

        // === Phase 3: ConfidenceGate — 领域验证 ===
        ConfidenceGate.Result gateResult = confidenceGate.evaluate(question, response.getSemanticIr());

        // === Phase 4: 构建响应 ===
        Map<String, Object> result = buildResponse(response, gateResult, startTime, question);
        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // /api/chat/detail — 调试查询接口（含 DSL + ES 原始结果）
    // ========================================================================

    @PostMapping(value = "/api/chat/detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chatDetail(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        long startTime = System.currentTimeMillis();
        log.info("[chat/detail] User question: {}", question);

        // === Phase 1: Workflow 检测 ===
        Map<String, Object> wfResult = tryWorkflowRoutes(question, startTime);
        if (wfResult != null) return ResponseEntity.ok(wfResult);

        // === Phase 2: Compile ===
        EsMindCompiler.QueryResponse response = compiler.compile(question);

        // === Phase 3: ConfidenceGate ===
        ConfidenceGate.Result gateResult = confidenceGate.evaluate(question, response.getSemanticIr());

        // === Phase 4: 构建详细响应 ===
        Map<String, Object> result = buildDetailResponse(response, gateResult, startTime, question);
        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // /api/agent/chat — AgentScope 代理查询（备用路径）
    // ========================================================================

    @PostMapping(value = "/api/agent/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> agentChat(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        long startTime = System.currentTimeMillis();
        log.info("[agent/chat] User question: {}", question);

        String answer = agent.query(question);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("elapsed_ms", System.currentTimeMillis() - startTime);
        result.put("mode", "agent");

        log.info("[agent/chat] Response in {}ms for: {}", result.get("elapsed_ms"), question);
        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // Health + Debug 端点
    // ========================================================================

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "mode", "v2-compiler+confidence-gate",
                "index", compiler != null ? "configured" : "unknown"
        ));
    }

    @GetMapping("/api/debug/parse")
    public ResponseEntity<Map<String, Object>> debugParse(@RequestParam(defaultValue = "\u95e8\u8bca\u5c31\u8bca\u8bb0\u5f55") String q) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            System.clearProperty("esmind.raw_llm_response");
            SemanticIR ir = compiler.getSemanticParser().parse(q);
            String llmRaw = System.getProperty("esmind.raw_llm_response", "");
            result.put("llm_raw", llmRaw);

            result.put("ir_before_resolve", Map.of(
                    "intent", ir.getIntent(),
                    "entities", ir.getEntities().stream().map(e -> Map.of(
                            "type", e.getType(),
                            "value", e.getValue(),
                            "timeType", e.getTimeType() != null ? e.getTimeType() : "",
                            "unit", e.getUnit() != null ? e.getUnit() : ""
                    )).toList(),
                    "aggregation", ir.getAggregation() != null ? Map.of(
                            "type", ir.getAggregation().getType()
                    ) : null
            ));

            SemanticIR resolved = compiler.getTemplateEngine().resolve(ir);
            result.put("ir_after_resolve", Map.of(
                    "entities", resolved.getEntities().stream().map(e -> Map.of(
                            "type", e.getType(),
                            "value", e.getValue(),
                            "table", e.getTable() != null ? e.getTable() : "",
                            "field", e.getField() != null ? e.getField() : "",
                            "clauseType", e.getClauseType() != null ? e.getClauseType() : ""
                    )).toList()
            ));

            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("error_type", e.getClass().getName());
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.printStackTrace(pw);
            String trace = sw.toString();
            result.put("stacktrace", trace.length() > 1000 ? trace.substring(0, 1000) : trace);
        }
        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // 内部工具方法
    // ========================================================================

    /**
     * 尝试 Workflow 路由（TREND_COMPARE / PIVOT / MULTI_CHAIN）。
     * 如果匹配则返回响应 Map，否则返回 null（走标准路径）。
     */
    private Map<String, Object> tryWorkflowRoutes(String question, long startTime) {
        if (patternDetector.isCompareQuery(question)) {
            return executeWorkflow(question, "trend_compare", startTime);
        }
        if (patternDetector.isPivotQuery(question)) {
            return executeWorkflow(question, "pivot", startTime);
        }
        if (patternDetector.isMultiChainQuery(question)) {
            return executeWorkflow(question, "multi_chain", startTime);
        }
        return null;
    }

    private Map<String, Object> executeWorkflow(String question, String mode, long startTime) {
        log.info("[chat] Routing to WorkflowEngine for {}: {}", mode, question);
        WorkflowEngine.WorkflowResult wfResult = workflowEngine.execute(question);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("elapsed_ms", System.currentTimeMillis() - startTime);
        result.put("decision", "WORKFLOW");
        result.put("mode", mode);
        result.put("answer", wfResult != null && wfResult.getAnswer() != null
                ? wfResult.getAnswer() : "\u67e5\u8be2\u5931\u8d25\u3002");
        result.put("confidence", 0.9);
        result.put("plan_type", "TASK");
        result.put("plan_reason", firstUpper(mode) + " query");
        return result;
    }

    /**
     * 构建标准回复（/api/chat）。
     */
    private Map<String, Object> buildResponse(EsMindCompiler.QueryResponse response,
                                               ConfidenceGate.Result gateResult,
                                               long startTime, String question) {
        long elapsed = System.currentTimeMillis() - startTime;
        Map<String, Object> result = baseResult(response, gateResult, elapsed);
        return result;
    }

    /**
     * 构建详细回复（/api/chat/detail），包含 DSL + ES 原始结果。
     */
    private Map<String, Object> buildDetailResponse(EsMindCompiler.QueryResponse response,
                                                     ConfidenceGate.Result gateResult,
                                                     long startTime, String question) {
        long elapsed = System.currentTimeMillis() - startTime;
        Map<String, Object> result = baseResult(response, gateResult, elapsed);

        result.put("dsl", response.getDsl() != null ? response.getDsl() : "");
        result.put("es_result", response.getEsRawResult() != null ? response.getEsRawResult() : "");
        boolean hasDetail = response.getDsl() != null && !response.getDsl().isEmpty()
                && response.getEsRawResult() != null && !response.getEsRawResult().isEmpty();
        result.put("has_detail", hasDetail);

        // 如果被拒绝/问候语/candidates，隐藏 detail
        if (gateResult.isRejected() || gateResult.isGreeting() || gateResult.needsCandidates()) {
            result.put("has_detail", false);
        }

        // 对 EXECUTE 结果，追加 markdown 表格
        if (gateResult.isExecutable() && response.getMarkdownTable() != null
                && !response.getMarkdownTable().isEmpty()) {
            String answer = (String) result.get("answer");
            if (answer != null && !answer.contains("|")) {
                result.put("answer", answer + "\n\n" + response.getMarkdownTable());
            }
        }

        if (response.getError() != null) {
            result.put("error", response.getError());
        }

        log.info("[chat/detail] Response in {}ms, decision={} (has_detail={}) for: {}",
                elapsed, result.get("decision"), hasDetail, question);

        return result;
    }

    /**
     * 构建公共结果字段（自信度、决策、回答等）。
     */
    private Map<String, Object> baseResult(EsMindCompiler.QueryResponse response,
                                            ConfidenceGate.Result gateResult,
                                            long elapsedMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("elapsed_ms", elapsedMs);
        result.put("confidence", gateResult.getConfidence());

        // 记录 QueryPlanner 决策
        QueryPlanner.Plan plan = null;
        if (response.getSemanticIr() != null) {
            try {
                SemanticIR resolved = compiler.getTemplateEngine().resolve(response.getSemanticIr());
                plan = queryPlanner.evaluate(resolved);
            } catch (Exception e) {
                // 非关键路径，失败不阻塞
            }
        }
        result.put("plan_type", plan != null ? plan.getType().name() : "N/A");
        result.put("plan_reason", plan != null ? plan.getReason() : "N/A");

        // 根据 ConfidenceGate 决策填充 answer
        if (gateResult.isRejected()) {
            result.put("decision", "REJECTED");
            result.put("answer", gateResult.getMessage());
        } else if (gateResult.isGreeting()) {
            result.put("decision", "GREETING");
            result.put("answer", gateResult.getMessage());
        } else if (gateResult.needsCandidates()) {
            result.put("decision", "CANDIDATES");
            result.put("answer", gateResult.getMessage());
        } else {
            result.put("decision", "EXECUTE");
            result.put("answer", response.getAnswer() != null ? response.getAnswer() : "(no response)");
        }

        if (response.getError() != null) {
            result.put("error", response.getError());
            if (response.getDsl() != null && !response.getDsl().isEmpty()) {
                result.put("dsl", response.getDsl());
            }
            if (!response.getDebugInfo().isEmpty()) {
                result.put("debug", response.getDebugInfo());
            }
        }

        return result;
    }

    private String firstUpper(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('_', ' ');
    }
}
