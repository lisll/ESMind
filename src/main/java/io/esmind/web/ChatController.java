package io.esmind.web;

import io.esmind.agent.EsMindCompiler;
import io.esmind.ast.ASTBuilder;
import io.esmind.compiler.EsRestClient;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.renderer.DSLRenderer;
import io.esmind.renderer.ResultTransformer;
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

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final EsMindCompiler compiler;

    public ChatController(SchemaRegistry schemaRegistry,
                          SemanticParser semanticParser,
                          TemplateEngine templateEngine,
                          ASTBuilder astBuilder,
                          DSLRenderer dslRenderer,
                          QueryValidator queryValidator,
                          EsRestClient esRestClient,
                          ResultTransformer resultTransformer,
                          String indexName) {
        this.compiler = new EsMindCompiler(
                semanticParser, templateEngine,
                dslRenderer, queryValidator,
                esRestClient, resultTransformer,
                indexName, schemaRegistry
        );
        log.info("ChatController initialized with v2 Compiler: index={}", indexName);
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
        EsMindCompiler.QueryResponse response = compiler.compile(question);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", response.getAnswer() != null ? response.getAnswer() : "(no response)");
        result.put("elapsed_ms", response.getTotalElapsedMs());

        if (response.getError() != null) {
            result.put("error", response.getError());
            if (response.getDsl() != null && !response.getDsl().isEmpty()) {
                result.put("dsl", response.getDsl());
            }
            if (!response.getDebugInfo().isEmpty()) {
                result.put("debug", response.getDebugInfo());
            }
        }

        log.info("[chat] Response in {}ms for: {}", response.getTotalElapsedMs(), question);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/api/chat/detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chatDetail(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        log.info("[chat/detail] User question: {}", question);
        EsMindCompiler.QueryResponse response = compiler.compile(question);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", response.getAnswer() != null ? response.getAnswer() : "(no response)");
        result.put("dsl", response.getDsl() != null ? response.getDsl() : "");
        result.put("es_result", response.getEsRawResult() != null ? response.getEsRawResult() : "");
        result.put("elapsed_ms", response.getTotalElapsedMs());

        boolean hasDetail = response.getDsl() != null && !response.getDsl().isEmpty()
                && response.getEsRawResult() != null && !response.getEsRawResult().isEmpty();
        result.put("has_detail", hasDetail);

        if (response.getError() != null) {
            result.put("error", response.getError());
        }

        if (response.getMarkdownTable() != null && !response.getMarkdownTable().isEmpty()) {
            String answer = (String) result.get("answer");
            if (answer != null && !answer.contains("|")) {
                result.put("answer", answer + "\n\n" + response.getMarkdownTable());
            }
        }

        log.info("[chat/detail] Response in {}ms (has_detail={}) for: {}",
                response.getTotalElapsedMs(), hasDetail, question);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "mode", "v2-compiler",
                "index", compiler != null ? "configured" : "unknown"
        ));
    }
}
