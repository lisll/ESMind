package io.esmind.web;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for the ESMind chat interface.
 *
 * <p>Serves the frontend HTML page and handles NL → ES query requests.
 * Each request is processed by the shared {@link HarnessAgent} instance.</p>
 */
@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private HarnessAgent agent;

    @Autowired
    private RuntimeContext ctx;

    // -------------------------------------------------------------------------
    // Serve the frontend page
    // -------------------------------------------------------------------------

    @GetMapping("/")
    public String index() {
        return "index"; // src/main/resources/templates/index.html
    }

    // -------------------------------------------------------------------------
    // Chat API — send a natural language query, get the agent's response
    // -------------------------------------------------------------------------

    @PostMapping(value = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        long startTime = System.currentTimeMillis();
        log.info("[chat] User question: {}", question);

        try {
            // Call the agent (blocking, reactive inside)
            Msg result = agent.call(
                    Msg.builder().role(MsgRole.USER).textContent(question).build(),
                    ctx
            ).block(java.time.Duration.ofSeconds(180));

            long elapsed = System.currentTimeMillis() - startTime;

            if (result == null) {
                log.warn("[chat] Agent returned null for: {}", question);
                return ResponseEntity.ok(Map.of(
                        "answer", "抱歉，Agent 未返回有效响应。",
                        "elapsed_ms", elapsed
                ));
            }

            String text = result.getTextContent();
            if (text == null || text.isBlank()) {
                text = "(Agent returned empty response)";
            }

            log.info("[chat] Response in {}ms for: {}", elapsed, question);

            return ResponseEntity.ok(Map.of(
                    "answer", text,
                    "elapsed_ms", elapsed
            ));

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[chat] Error processing: {} ({}ms)", question, elapsed, e);
            return ResponseEntity.ok(Map.of(
                    "answer", "处理查询时出错: " + e.getMessage(),
                    "error", e.getClass().getSimpleName(),
                    "elapsed_ms", elapsed
            ));
        }
    }

    // -------------------------------------------------------------------------
    // Health check endpoint
    // -------------------------------------------------------------------------

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "agent", "esmind",
                "session", ctx.getSessionId()
        ));
    }
}
