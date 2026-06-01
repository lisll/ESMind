package io.esmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.esmind.ast.ASTBuilder;
import io.esmind.ast.QueryNode;
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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ESMind Compiler — 主入口。
 * <p>
 * 编译管线：
 * <pre>
 *   NL Query → LLM/FastPath → Resolution → ASTBuilder → DSLRenderer → ES Client
 * </pre>
 */
public class EsMindCompiler {

    private static final Logger log = LoggerFactory.getLogger(EsMindCompiler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SemanticParser semanticParser;
    private final TemplateEngine templateEngine;
    private final DSLRenderer dslRenderer;
    private final QueryValidator queryValidator;
    private final EsRestClient esClient;
    private final ResultTransformer resultTransformer;
    private final String indexName;
    private final SchemaRegistry schemaRegistry;

    public EsMindCompiler(SemanticParser parser,
                          TemplateEngine templateEngine,
                          DSLRenderer renderer,
                          QueryValidator validator,
                          EsRestClient esClient,
                          ResultTransformer resultTransformer,
                          String indexName) {
        this(parser, templateEngine, renderer, validator, esClient, resultTransformer, indexName, null);
    }

    public EsMindCompiler(SemanticParser parser,
                          TemplateEngine templateEngine,
                          DSLRenderer renderer,
                          QueryValidator validator,
                          EsRestClient esClient,
                          ResultTransformer resultTransformer,
                          String indexName,
                          SchemaRegistry schemaRegistry) {
        this.semanticParser = parser;
        this.templateEngine = templateEngine;
        this.dslRenderer = renderer;
        this.queryValidator = validator;
        this.esClient = esClient;
        this.resultTransformer = resultTransformer;
        this.indexName = indexName;
        this.schemaRegistry = schemaRegistry;
    }

    /**
     * 编译并执行完整查询（LLM Path）。
     */
    public QueryResponse compile(String nlQuery) {
        long startTime = System.currentTimeMillis();
        QueryResponse response = new QueryResponse();
        response.setNlQuery(nlQuery);

        try {
            // === Step 1: LLM Semantic Parse ===
            log.info("[1/5] LLM parse: {}", nlQuery);
            SemanticIR ir = semanticParser.parse(nlQuery);
            response.setSemanticIr(ir);

            // === Step 2: Resolution ===
            log.info("[2/5] Resolve: {} entities", ir.getEntities().size());
            ir = templateEngine.resolve(ir);
            response.setSemanticIr(ir);

            // === Step 3: AST Build ===
            log.info("[3/5] AST build: {} resolved entities", ir.getEntities().size());
            ASTBuilder astBuilder = new ASTBuilder();
            ASTBuilder.QueryContainer container = astBuilder.build(ir);
            response.setQueryNode(container.getQuery());

            // === Step 4: Validation ===
            if (container.getQuery() != null) {
                QueryValidator.ValidationResult vr = queryValidator.validate(container.getQuery());
                if (vr.hasErrors()) {
                    log.warn("Validation warnings: {}", vr.summary());
                }
                response.setValidationResult(vr);
            }

            // === Step 5: DSL Render ===
            String dsl = dslRenderer.render(container);
            response.setDsl(dsl);
            log.debug("Generated DSL: {}", dsl);

            // === Step 6: Execute ===
            log.info("[6/6] Execute ES query");
            String esResult;
            long esStart = System.currentTimeMillis();

            if ("patient_count".equals(ir.getIntent()) && ir.getAggregation() != null
                    && "count".equals(ir.getAggregation().getType())) {
                // _count API 不支持 size 和 aggs
                com.fasterxml.jackson.databind.JsonNode dslJson = MAPPER.readTree(dsl);
                com.fasterxml.jackson.databind.node.ObjectNode dslObj = (com.fasterxml.jackson.databind.node.ObjectNode) dslJson;
                dslObj.remove("size");
                dslObj.remove("aggs");
                String countDsl = MAPPER.writeValueAsString(dslObj);
                String countResult = esClient.count(indexName, countDsl);
                response.setEsRawResult(countResult);
                com.fasterxml.jackson.databind.JsonNode countJson = MAPPER.readTree(countResult);
                long count = countJson.get("count").asLong();
                response.setAnswer("共查询到 " + count + " 名患者。");
            } else if (ir.getAggregation() != null && "date_histogram".equals(ir.getAggregation().getType())) {
                // date_histogram 聚合查询
                esResult = esClient.search(indexName, dsl);
                response.setEsRawResult(esResult);
                com.fasterxml.jackson.databind.JsonNode esJson = MAPPER.readTree(esResult);
                String aggName = ir.getAggregation().getName() != null ? ir.getAggregation().getName() : "visit_by_month";
                com.fasterxml.jackson.databind.JsonNode aggs = esJson.get("aggregations");
                if (aggs != null && aggs.has(aggName)) {
                    com.fasterxml.jackson.databind.JsonNode aggRoot = aggs.get(aggName);
                    // 兼容 nested 聚合：结果在 aggRoot 的子字段下
                    com.fasterxml.jackson.databind.JsonNode buckets = aggRoot.get("buckets");
                    if (buckets == null) {
                        // nested aggregation → buckets 在子聚合字段下（如 by_shouyezhenduan_diagnosis_time）
                        Iterator<String> fieldNames = aggRoot.fieldNames();
                        while (fieldNames.hasNext()) {
                            String fn = fieldNames.next();
                            if (fn.startsWith("by_")) {
                                buckets = aggRoot.get(fn).get("buckets");
                                break;
                            }
                        }
                    }
                    if (buckets != null && buckets.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        // 兼容 ES 6.x (total=数字) 和 7.x+ (total={value: N})
                        com.fasterxml.jackson.databind.JsonNode totalNode = esJson.get("hits").get("total");
                        String totalStr = totalNode.isObject()
                                ? totalNode.get("value").asText()
                                : totalNode.asText();
                        sb.append("共查询到 **").append(totalStr)
                            .append("** 条记录。按时间分布如下：\n\n");
                        sb.append("| 月份 | 记录数 |\n|------|--------|\n");
                        long totalRecords = 0;
                        for (com.fasterxml.jackson.databind.JsonNode bucket : buckets) {
                            String key = bucket.has("key_as_string") ? bucket.get("key_as_string").asText()
                                    : String.valueOf(bucket.get("key").asLong());
                            long count = bucket.get("doc_count").asLong();
                            sb.append("| ").append(key).append(" | ").append(count).append(" |\n");
                            totalRecords += count;
                        }
                        sb.append("| **合计** | **").append(totalRecords).append("** |\n");
                        response.setAnswer(sb.toString());
                    }
                }
                if (response.getAnswer() == null) {
                    // 聚合结果为空
                    com.fasterxml.jackson.databind.JsonNode totalNode = esJson.get("hits").get("total");
                    String totalStr = totalNode.isObject()
                            ? totalNode.get("value").asText()
                            : totalNode.asText();
                    response.setAnswer("共查询到 **" + totalStr + "** 条记录，无聚合数据。");
                }
            } else {
                esResult = esClient.search(indexName, dsl);
                response.setEsRawResult(esResult);
                ResultTransformer.TransformResult tr = resultTransformer.transform(esResult, container);
                response.setAnswer(tr.getSummary());
                response.setMarkdownTable(tr.getMarkdownTable());
                response.setTotalHits(tr.getTotalHits());
            }

            long esElapsed = System.currentTimeMillis() - esStart;
            response.setEsElapsedMs(esElapsed);
            log.info("ES query completed in {}ms", esElapsed);

        } catch (Exception e) {
            log.error("Compilation failed: {}", e.getMessage(), e);
            response.setError(e.getMessage());
            String msg = e.getMessage();

            String llmRaw = System.getProperty("esmind.raw_llm_response", "");
            if (!llmRaw.isEmpty()) {
                response.addDebugInfo("raw_llm_response", llmRaw);
            }

            if (msg != null && (msg.contains("Unrecognized field") || msg.contains("cannot be parsed")
                    || msg.contains("JsonParseException") || msg.contains("JsonMappingException"))) {
                String rawLlm = response.getDebugInfo().getOrDefault("raw_llm_response", "").toString();
                if (!rawLlm.isEmpty()) {
                    response.setAnswer("查询解析失败，LLM 返回了无法解析的格式。原始返回值：\n```json\n"
                            + rawLlm + "\n```");
                } else {
                    response.setAnswer("查询解析失败：" + msg);
                }
            } else {
                response.setAnswer("查询失败：" + msg);
            }
        }

        response.setTotalElapsedMs(System.currentTimeMillis() - startTime);
        return response;
    }

    /**
     * 从已解析的 SemanticIR 编译执行（FastPath 入口）。
     */
    public QueryResponse compile(SemanticIR ir) {
        long startTime = System.currentTimeMillis();
        QueryResponse response = new QueryResponse();
        response.setSemanticIr(ir);

        try {
            // Step 1: Resolution (already resolved for FastPath, but safe to call)
            log.info("[1/4] FastPath compile: {} entities", ir.getEntities().size());
            ir = templateEngine.resolve(ir);

            // Step 2: AST Build
            ASTBuilder astBuilder = new ASTBuilder();
            ASTBuilder.QueryContainer container = astBuilder.build(ir);
            response.setQueryNode(container.getQuery());

            // Step 3: DSL Render
            String dsl = dslRenderer.render(container);
            response.setDsl(dsl);

            // Step 4: Execute
            long esStart = System.currentTimeMillis();
            String esResult = esClient.search(indexName, dsl);
            response.setEsRawResult(esResult);
            ResultTransformer.TransformResult tr = resultTransformer.transform(esResult, container);
            response.setAnswer(tr.getSummary());
            response.setMarkdownTable(tr.getMarkdownTable());
            response.setTotalHits(tr.getTotalHits());
            response.setEsElapsedMs(System.currentTimeMillis() - esStart);

        } catch (Exception e) {
            log.error("FastPath compilation failed: {}", e.getMessage(), e);
            response.setError(e.getMessage());
            response.setAnswer("查询失败：" + e.getMessage());
        }

        response.setTotalElapsedMs(System.currentTimeMillis() - startTime);
        return response;
    }

    // Getter for debug endpoints
    public SemanticParser getSemanticParser() { return semanticParser; }
    public TemplateEngine getTemplateEngine() { return templateEngine; }

    // ===== Response =====

    public static class QueryResponse {
        private String nlQuery;
        private SemanticIR semanticIr;
        private QueryNode queryNode;
        private String dsl;
        private String esRawResult;
        private String answer;
        private String error;
        private String markdownTable;
        private long totalHits;
        private QueryValidator.ValidationResult validationResult;
        private long totalElapsedMs;
        private long esElapsedMs;
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
        public String getMarkdownTable() { return markdownTable; }
        public void setMarkdownTable(String v) { this.markdownTable = v; }
        public long getTotalHits() { return totalHits; }
        public void setTotalHits(long v) { this.totalHits = v; }
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
