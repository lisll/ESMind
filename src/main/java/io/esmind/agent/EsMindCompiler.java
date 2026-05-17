package io.esmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.esmind.ast.ASTBuilder;
import io.esmind.ast.QueryNode;
import io.esmind.compiler.EsRestClient;
import io.esmind.compiler.SchemaLoader;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.renderer.DSLRenderer;
import io.esmind.semantic.SemanticIR;
import io.esmind.semantic.SemanticParser;
import io.esmind.strategy.StrategySelector;
import io.esmind.validator.QueryValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ESMind Compiler — 主入口。
 *
 * <p>将自然语言查询编译为 ES DSL 并执行。
 * 完整的编译管线：NL → SemanticParser → ASTBuilder → DSLRenderer → ES Client
 */
public class EsMindCompiler {

    private static final Logger log = LoggerFactory.getLogger(EsMindCompiler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaRegistry schema;
    private final SemanticParser semanticParser;
    private final ASTBuilder astBuilder;
    private final DSLRenderer dslRenderer;
    private final QueryValidator queryValidator;
    private final EsRestClient esClient;
    private final String indexName;

    public EsMindCompiler(SchemaRegistry schema, SemanticParser parser,
                          ASTBuilder astBuilder, DSLRenderer renderer,
                          QueryValidator validator, EsRestClient esClient,
                          String indexName) {
        this.schema = schema;
        this.semanticParser = parser;
        this.astBuilder = astBuilder;
        this.dslRenderer = renderer;
        this.queryValidator = validator;
        this.esClient = esClient;
        this.indexName = indexName;
    }

    /**
     * 编译并执行完整查询。
     *
     * @param nlQuery 自然语言查询
     * @return 查询结果
     */
    public QueryResponse compile(String nlQuery) {
        long startTime = System.currentTimeMillis();
        QueryResponse response = new QueryResponse();
        response.setNlQuery(nlQuery);

        try {
            // === Step 1: Semantic Parse ===
            log.info("[1/5] Semantic parse: {}", nlQuery);
            SemanticIR ir = semanticParser.parse(nlQuery);
            response.setSemanticIr(ir);

            // === Step 2: AST Build ===
            log.info("[2/5] AST build: intent={}, entities={}", ir.getIntent(), ir.getEntities().size());
            ASTBuilder.QueryContainer container = astBuilder.build(ir);
            response.setQueryNode(container.getQuery());

            // === Step 3: Validation ===
            log.info("[3/5] Validate");
            if (container.getQuery() != null) {
                QueryValidator.ValidationResult vr = queryValidator.validate(container.getQuery());
                if (vr.hasErrors()) {
                    log.warn("Validation warnings: {}", vr.summary());
                }
                response.setValidationResult(vr);
            }

            // === Step 4: DSL Render ===
            log.info("[4/5] DSL render");
            String dsl = dslRenderer.render(container);
            response.setDsl(dsl);
            log.debug("Generated DSL: {}", dsl);

            // === Step 5: Execute ===
            log.info("[5/5] Execute ES query");
            String esResult;
            long esStart = System.currentTimeMillis();

            if ("patient_count".equals(ir.getIntent()) && ir.getAggregation() != null
                    && "count".equals(ir.getAggregation().getType())) {
                // Count query
                String countResult = esClient.count(indexName, dsl);
                response.setEsRawResult(countResult);
                // Parse count from response
                com.fasterxml.jackson.databind.JsonNode countJson = MAPPER.readTree(countResult);
                long count = countJson.get("count").asLong();
                response.setAnswer("共查询到 " + count + " 名患者。");
            } else {
                // Search query
                esResult = esClient.search(indexName, dsl);
                response.setEsRawResult(esResult);
                response.setAnswer(formatAnswer(esResult));
            }

            long esElapsed = System.currentTimeMillis() - esStart;
            response.setEsElapsedMs(esElapsed);
            log.info("ES query completed in {}ms", esElapsed);

        } catch (Exception e) {
            log.error("Compilation failed: {}", e.getMessage(), e);
            response.setError(e.getMessage());
            response.setAnswer("查询失败: " + e.getMessage());
        }

        response.setTotalElapsedMs(System.currentTimeMillis() - startTime);
        return response;
    }

    private String formatAnswer(String esResult) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(esResult);
            com.fasterxml.jackson.databind.JsonNode hits = root.get("hits");
            if (hits == null) return "ES 返回了空结果。";

            long total = hits.get("total").asLong();
            if (total == 0) return "未查询到符合条件的患者。";

            return "共查询到 " + total + " 条结果。";
        } catch (Exception e) {
            return "查询完成，结果解析出错: " + e.getMessage();
        }
    }

    // ===== Response =====

    public static class QueryResponse {
        private String nlQuery;
        private SemanticIR semanticIr;
        private QueryNode queryNode;
        private String dsl;
        private String esRawResult;
        private String answer;
        private String error;
        private QueryValidator.ValidationResult validationResult;
        private long totalElapsedMs;
        private long esElapsedMs;

        // We also store intermediate outputs for explainability
        private final Map<String, Object> debugInfo = new LinkedHashMap<>();

        public String getNlQuery() { return nlQuery; }
        public void setNlQuery(String v) { this.nlQuery = v; }

        public SemanticIR getSemanticIr() { return semanticIr; }
        public void setSemanticIr(SemanticIR v) { this.semanticIr = v; }

        public QueryNode getQueryNode() { return queryNode; }
        public void setQueryNode(QueryNode v) { this.queryNode = v; }

        public String getDsl() { return dsl; }
        public void setDsl(String v) { this.dsl = v; }

        public String getEsRawResult() { return esRawResult; }
        public void setEsRawResult(String v) { this.esRawResult = v; }

        public String getAnswer() { return answer; }
        public void setAnswer(String v) { this.answer = v; }

        public String getError() { return error; }
        public void setError(String v) { this.error = v; }

        public QueryValidator.ValidationResult getValidationResult() { return validationResult; }
        public void setValidationResult(QueryValidator.ValidationResult v) { this.validationResult = v; }

        public long getTotalElapsedMs() { return totalElapsedMs; }
        public void setTotalElapsedMs(long v) { this.totalElapsedMs = v; }

        public long getEsElapsedMs() { return esElapsedMs; }
        public void setEsElapsedMs(long v) { this.esElapsedMs = v; }

        public Map<String, Object> getDebugInfo() { return debugInfo; }
        public void addDebugInfo(String key, Object value) { debugInfo.put(key, value); }
    }
}
