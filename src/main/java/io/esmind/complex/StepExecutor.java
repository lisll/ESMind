package io.esmind.complex;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import io.esmind.compiler.EsRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个步骤执行器：生成 DSL → 执行 ES 查询 → 返回结果。
 */
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final EsRestClient esRestClient;
    private final String indexName;

    public StepExecutor(EsRestClient esRestClient, String indexName) {
        this.esRestClient = esRestClient;
        this.indexName = indexName;
    }

    /**
     * 执行单个步骤。
     *
     * @param step 步骤定义
     * @param context 上下文（包含依赖步骤的结果）
     * @return 步骤执行结果
     */
    public StepResult execute(ExtractStep step, Map<String, Object> context) {
        log.info("StepExecutor: executing step '{}' on table '{}'", step.getId(), step.getTable());

        try {
            // 1. 构建查询
            Query query = buildQuery(step, context);

            // 2. 执行查询
            SearchResponse<Map> response = esRestClient.getClient().search(s -> s
                    .index(indexName)
                    .query(query)
                    .source(src -> src.filter(f -> f.includes(step.getFields() != null ? step.getFields() : List.of())))
                    .size(100), Map.class);

            // 3. 解析结果
            StepResult result = parseResponse(step, response);

            log.info("StepExecutor: step '{}' completed, {} hits", step.getId(), result.getHits().size());
            return result;

        } catch (IOException e) {
            log.error("StepExecutor: error executing step '{}'", step.getId(), e);
            return StepResult.error(step.getId(), e.getMessage());
        }
    }

    /**
     * 构建 ES 查询。
     */
    private Query buildQuery(ExtractStep step, Map<String, Object> context) {
        List<Query> mustClauses = new ArrayList<>();

        // 1. 总是加上 patient_id 过滤（从 sources 中取）
        Object patientId = context.get("patient_id");
        if (patientId != null) {
            mustClauses.add(Query.of(q -> q.matchPhrase(m -> m
                    .field("patient_id")
                    .query(patientId.toString()))));
        }

        // 2. 加上步骤自身的 filters
        if (step.getFilters() != null) {
            for (ExtractStep.Filter filter : step.getFilters()) {
                Query filterQuery = buildFilterQuery(filter, context);
                if (filterQuery != null) {
                    mustClauses.add(filterQuery);
                }
            }
        }

        // 3. 组合成 bool query
        if (mustClauses.isEmpty()) {
            return Query.of(q -> q.matchAll(m -> m));
        } else if (mustClauses.size() == 1) {
            return mustClauses.get(0);
        } else {
            return Query.of(q -> q.bool(b -> b.must(mustClauses)));
        }
    }

    /**
     * 构建单个过滤器查询。
     */
    private Query buildFilterQuery(ExtractStep.Filter filter, Map<String, Object> context) {
        Object value = filter.getValue();

        // 处理依赖引用：@stepId.field
        if (value instanceof String && ((String) value).startsWith("@")) {
            String ref = ((String) value).substring(1);
            String[] parts = ref.split("\\.", 2);
            if (parts.length == 2) {
                String stepId = parts[0];
                String field = parts[1];
                StepResult depResult = (StepResult) context.get(stepId);
                if (depResult != null && !depResult.getHits().isEmpty()) {
                    Map<String, Object> firstHit = depResult.getHits().get(0);
                    value = firstHit.get(field);
                }
            }
        }

        if (value == null) return null;

        // 根据操作符构建查询
        return switch (filter.getOp()) {
            case "=" -> Query.of(q -> q.matchPhrase(m -> m
                    .field(filter.getField())
                    .query(value.toString())));
            case ">" -> Query.of(q -> q.range(r -> r
                    .field(filter.getField())
                    .gt(JsonData.of(value))));
            case "<" -> Query.of(q -> q.range(r -> r
                    .field(filter.getField())
                    .lt(JsonData.of(value))));
            case ">=" -> Query.of(q -> q.range(r -> r
                    .field(filter.getField())
                    .gte(JsonData.of(value))));
            case "<=" -> Query.of(q -> q.range(r -> r
                    .field(filter.getField())
                    .lte(JsonData.of(value))));
            default -> null;
        };
    }

    /**
     * 解析 ES 响应。
     */
    private StepResult parseResponse(ExtractStep step, SearchResponse<Map> response) {
        StepResult result = new StepResult(step.getId());

        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            if (source != null) {
                result.getHits().add(source);
            }
        }

        return result;
    }

    // --- 步骤结果类 ---

    public static class StepResult {
        private final String stepId;
        private final List<Map<String, Object>> hits;
        private boolean success;
        private String error;

        public StepResult(String stepId) {
            this.stepId = stepId;
            this.hits = new ArrayList<>();
            this.success = true;
        }

        public static StepResult error(String stepId, String error) {
            StepResult result = new StepResult(stepId);
            result.success = false;
            result.error = error;
            return result;
        }

        public String getStepId() { return stepId; }
        public List<Map<String, Object>> getHits() { return hits; }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }
    }
}
