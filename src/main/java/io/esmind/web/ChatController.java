package io.esmind.web;

import io.esmind.agent.ConfidenceGate;
import io.esmind.agent.EsMindCompiler;
import io.esmind.agent.MedicalQueryAgent;
import io.esmind.ast.ASTBuilder;
import io.esmind.compiler.EsRestClient;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.renderer.DSLRenderer;
import io.esmind.renderer.ResultTransformer;
import io.esmind.semantic.SemanticIR;
import io.esmind.semantic.SemanticParser;
import io.esmind.template.TemplateEngine;
import io.esmind.validator.QueryValidator;
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

    public ChatController(SchemaRegistry schemaRegistry,
                          SemanticParser semanticParser,
                          TemplateEngine templateEngine,
                          ASTBuilder astBuilder,
                          DSLRenderer dslRenderer,
                          QueryValidator queryValidator,
                          EsRestClient esRestClient,
                          ResultTransformer resultTransformer,
                          String indexName,
                          MedicalQueryAgent medicalQueryAgent) {
        this.compiler = new EsMindCompiler(
                semanticParser, templateEngine,
                dslRenderer, queryValidator,
                esRestClient, resultTransformer,
                indexName, schemaRegistry
        );
        this.confidenceGate = new ConfidenceGate();
        this.agent = medicalQueryAgent;
        log.info("ChatController initialized with v2 Compiler + ConfidenceGate: index={}", indexName);
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping(value = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        log.info("[chat] User question: {}", question);

        // === Phase 0: Confidence Gate (PRE) ===
        ConfidenceGate.Result preResult = confidenceGate.evaluatePre(question);
        if (preResult.isRejected()) {
            log.info("[chat] ConfidenceGate REJECTED: {}", question);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", preResult.getMessage());
            result.put("confidence", 0.0);
            result.put("decision", "REJECTED");
            result.put("elapsed_ms", 0);
            return ResponseEntity.ok(result);
        }

        if (preResult.isGreeting()) {
            log.info("[chat] Greeting: {}", question);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", preResult.getMessage());
            result.put("confidence", 1.0);
            result.put("decision", "GREETING");
            result.put("elapsed_ms", 0);
            return ResponseEntity.ok(result);
        }

        // === Normal compile ===
        EsMindCompiler.QueryResponse response = compiler.compile(question);

        // === Phase 0: Confidence Gate (POST) — LLM 解析检查 ===
        ConfidenceGate.Result postResult = confidenceGate.evaluatePost(question, response.getSemanticIr());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("elapsed_ms", response.getTotalElapsedMs());
        result.put("confidence", postResult.getConfidence());

        if (postResult.needsCandidates()) {
            result.put("decision", "CANDIDATES");
            result.put("answer", postResult.getMessage());
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

        log.info("[chat] Response in {}ms, decision={} for: {}",
                response.getTotalElapsedMs(), result.get("decision"), question);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/api/chat/detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chatDetail(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        log.info("[chat/detail] User question: {}", question);

        // === Phase 0: Confidence Gate (PRE) ===
        ConfidenceGate.Result preResult = confidenceGate.evaluatePre(question);
        if (preResult.isRejected()) {
            log.info("[chat/detail] ConfidenceGate REJECTED: {}", question);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", preResult.getMessage());
            result.put("confidence", 0.0);
            result.put("decision", "REJECTED");
            result.put("elapsed_ms", 0);
            return ResponseEntity.ok(result);
        }

        if (preResult.isGreeting()) {
            log.info("[chat/detail] Greeting: {}", question);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", preResult.getMessage());
            result.put("confidence", 1.0);
            result.put("decision", "GREETING");
            result.put("elapsed_ms", 0);
            return ResponseEntity.ok(result);
        }

        // === Normal compile ===
        EsMindCompiler.QueryResponse response = compiler.compile(question);

        // === Phase 0: Confidence Gate (POST) — LLM 解析检查 ===
        ConfidenceGate.Result postResult = confidenceGate.evaluatePost(question, response.getSemanticIr());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("elapsed_ms", response.getTotalElapsedMs());
        result.put("confidence", postResult.getConfidence());

        // 全部先扔进去，后面根据 decision 决定展示哪些
        result.put("dsl", response.getDsl() != null ? response.getDsl() : "");
        result.put("es_result", response.getEsRawResult() != null ? response.getEsRawResult() : "");
        boolean hasDetail = response.getDsl() != null && !response.getDsl().isEmpty()
                && response.getEsRawResult() != null && !response.getEsRawResult().isEmpty();
        result.put("has_detail", hasDetail);

        if (postResult.needsCandidates()) {
            result.put("decision", "CANDIDATES");
            result.put("answer", postResult.getMessage());
            result.put("has_detail", false);
        } else {
            result.put("decision", "EXECUTE");
            result.put("answer", response.getAnswer() != null ? response.getAnswer() : "(no response)");

            if (response.getMarkdownTable() != null && !response.getMarkdownTable().isEmpty()) {
                String answer = (String) result.get("answer");
                if (answer != null && !answer.contains("|")) {
                    result.put("answer", answer + "\n\n" + response.getMarkdownTable());
                }
            }
        }

        if (response.getError() != null) {
            result.put("error", response.getError());
        }

        log.info("[chat/detail] Response in {}ms, decision={} (has_detail={}) for: {}",
                response.getTotalElapsedMs(), result.get("decision"), hasDetail, question);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/api/agent/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> agentChat(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        log.info("[agent/chat] User question: {}", question);

        long startTime = System.currentTimeMillis();

        // ConfidenceGate pre-check
        ConfidenceGate.Result preResult = confidenceGate.evaluatePre(question);
        if (preResult.isRejected()) {
            log.info("[agent/chat] ConfidenceGate REJECTED: {}", question);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", preResult.getMessage());
            result.put("confidence", 0.0);
            result.put("decision", "REJECTED");
            result.put("elapsed_ms", 0);
            result.put("mode", "agent");
            return ResponseEntity.ok(result);
        }

        // AgentScope agent 处理
        String answer = agent.query(question);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("elapsed_ms", System.currentTimeMillis() - startTime);
        result.put("mode", "agent");

        log.info("[agent/chat] Response in {}ms for: {}", result.get("elapsed_ms"), question);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "mode", "v2-compiler+confidence-gate",
                "index", compiler != null ? "configured" : "unknown"
        ));
    }

    /**
     * Debug endpoint: 返回 LLM 原始输出 + SemanticIR
     */
    @GetMapping("/api/debug/parse")
    public ResponseEntity<Map<String, Object>> debugParse(@RequestParam(defaultValue = "门诊就诊记录") String q) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Step 1: LLM raw output
            System.clearProperty("esmind.raw_llm_response");
            SemanticIR ir = compiler.getSemanticParser().parse(q);
            String llmRaw = System.getProperty("esmind.raw_llm_response", "");
            result.put("llm_raw", llmRaw);

            // Step 2: SemanticIR
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

            // Step 3: After resolution
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
            // capture cause chain
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.printStackTrace(pw);
            String trace = sw.toString();
            result.put("stacktrace", trace.length() > 1000 ? trace.substring(0, 1000) : trace);
        }
        return ResponseEntity.ok(result);
    }
}
