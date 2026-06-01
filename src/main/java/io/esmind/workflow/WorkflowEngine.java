package io.esmind.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.esmind.agent.EsMindCompiler;
import io.esmind.compiler.EsRestClient;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.semantic.SemanticIR;
import io.esmind.template.TemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * WorkflowEngine — 三模式工作流执行引擎。
 * <p>
 * 支持三种模式：
 * <ol>
 *   <li>PIVOT: 患者队列 → 跨表明细（"发烧的患者白细胞"）</li>
 *   <li>MULTI_CHAIN: 多步条件链（逐步收窄）</li>
 *   <li>TREND_COMPARE: 趋势对比（"上月vs本月门诊量"）</li>
 * </ol>
 * <p>
 * 流程：PatternDetector.detect() → buildPlan() → execute()
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 根级 patient_id terms 字段 */
    private static final String ROOT_PATIENT_ID_FIELD = "patient.patient_id.raw";

    private final EsMindCompiler compiler;
    private final EsRestClient esClient;
    private final String indexName;
    private final SchemaRegistry schemaRegistry;
    private final TemplateEngine templateEngine;
    private final PatternDetector patternDetector;

    public WorkflowEngine(EsMindCompiler compiler,
                          EsRestClient esClient,
                          String indexName,
                          SchemaRegistry schemaRegistry,
                          TemplateEngine templateEngine) {
        this.compiler = compiler;
        this.esClient = esClient;
        this.indexName = indexName;
        this.schemaRegistry = schemaRegistry;
        this.templateEngine = templateEngine;
        this.patternDetector = new PatternDetector();
    }

    // ========================================================================
    // Entry Point
    // ========================================================================

    public WorkflowResult execute(String nlQuery) {
        long startTime = System.currentTimeMillis();
        log.info("[WorkflowEngine] execute: {}", nlQuery);

        try {
            // Step 1: Detect pattern
            PatternDetector.DetectionResult detection = patternDetector.detect(nlQuery);
            if (!detection.isFound()) {
                log.info("[WorkflowEngine] No workflow pattern detected");
                return null;
            }

            log.info("[WorkflowEngine] Pattern: {} — {}", detection.getPattern(), detection.getDescription());

            // Step 2: Build plan
            WorkflowPlan plan = buildPlan(detection, nlQuery);
            plan.setStatus(WorkflowPlan.Status.RUNNING);

            // Step 3: Execute
            WorkflowResult result = switch (plan.getType()) {
                case PIVOT -> executePivot(plan);
                case TREND_COMPARE -> executeTrendCompare(plan);
                case MULTI_CHAIN -> executeMultiChain(plan);
            };

            long elapsed = System.currentTimeMillis() - startTime;
            if (result != null) {
                result.setElapsedMs(elapsed);
                plan.setTotalElapsedMs(elapsed);
            }
            plan.setStatus(WorkflowPlan.Status.COMPLETED);
            log.info("[WorkflowEngine] completed in {}ms: {}", elapsed, plan.getDescription());
            return result;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[WorkflowEngine] failed: {}", e.getMessage(), e);
            return new WorkflowResult(nlQuery, null,
                    "查询执行失败：" + e.getMessage(), null, elapsed);
        }
    }

    // ========================================================================
    // Plan Builder
    // ========================================================================

    private WorkflowPlan buildPlan(PatternDetector.DetectionResult detection, String nlQuery) {
        switch (detection.getPattern()) {
            case PIVOT: return buildPivotPlan(detection, nlQuery);
            case TREND_COMPARE: return buildComparePlan(detection, nlQuery);
            case MULTI_CHAIN: return buildChainPlan(detection, nlQuery);
            default: throw new IllegalStateException("Unknown pattern: " + detection.getPattern());
        }
    }

    private WorkflowPlan buildPivotPlan(PatternDetector.DetectionResult detection, String nlQuery) {
        String cohortQuery = detection.getSegment(0);
        String detailQuery = detection.getSegment(1);

        WorkflowPlan plan = new WorkflowPlan(WorkflowPlan.Type.PIVOT, nlQuery,
                "患者队列 pivot: " + cohortQuery + " → " + detailQuery);

        plan.addStep(new WorkflowStep("step-0", WorkflowStep.Type.COHORT,
                "查询" + cohortQuery + "的患者")
                .withNlQuery(cohortQuery));

        plan.addStep(new WorkflowStep("step-1", WorkflowStep.Type.DETAIL,
                "查询" + detailQuery + "（限制在 step-0 患者范围内）")
                .withNlQuery(detailQuery)
                .withDependsOn("step-0"));

        return plan;
    }

    private WorkflowPlan buildComparePlan(PatternDetector.DetectionResult detection, String nlQuery) {
        String leftSegment = detection.getSegment(0);
        String rightSegment = detection.getSegment(1);

        // 推测时间范围标签：左边通常有时间词
        String timeLabelA = extractTimeLabel(leftSegment);
        String timeLabelB = extractTimeLabel(rightSegment);

        WorkflowPlan plan = new WorkflowPlan(WorkflowPlan.Type.TREND_COMPARE, nlQuery,
                "趋势对比: " + timeLabelA + " vs " + timeLabelB);
        plan.setTimeRangeALabel(timeLabelA);
        plan.setTimeRangeBLabel(timeLabelB);

        plan.addStep(new WorkflowStep("step-a", WorkflowStep.Type.COMPARE,
                "查询" + timeLabelA + "数据")
                .withNlQuery(nlQuery) // 完整查询，靠时间属性区分
                .withTimeRangeLabel(timeLabelA));

        plan.addStep(new WorkflowStep("step-b", WorkflowStep.Type.COMPARE,
                "查询" + timeLabelB + "数据")
                .withNlQuery(nlQuery)
                .withTimeRangeLabel(timeLabelB));

        return plan;
    }

    private WorkflowPlan buildChainPlan(PatternDetector.DetectionResult detection, String nlQuery) {
        // MULTI_CHAIN 暂回退为 PIVOT 处理
        return buildPivotPlan(detection, nlQuery);
    }

    // ========================================================================
    // PIVOT Executor
    // ========================================================================

    private WorkflowResult executePivot(WorkflowPlan plan) throws Exception {
        WorkflowStep cohortStep = plan.getStep(0);
        WorkflowStep detailStep = plan.getStep(1);

        // Step 1: COHORT
        log.info("[WorkflowEngine] Step 1 (COHORT): {}", cohortStep.getNlQuery());
        cohortStep.setStatus(WorkflowStep.Status.RUNNING);
        EsMindCompiler.QueryResponse cohortResp = compiler.compile(cohortStep.getNlQuery());
        cohortStep.setResultText(cohortResp.getAnswer());
        cohortStep.setEsRawResult(cohortResp.getEsRawResult());
        cohortStep.setDsl(cohortResp.getDsl());
        cohortStep.setStatus(WorkflowStep.Status.COMPLETED);

        if (cohortResp.getError() != null) {
            cohortStep.setError(cohortResp.getError());
            cohortStep.setStatus(WorkflowStep.Status.FAILED);
            return failResult(plan, "第一步（患者查询）失败：" + cohortResp.getError());
        }

        // Extract patient_ids
        List<String> patientIds = extractPatientIds(cohortResp.getEsRawResult());
        log.info("[WorkflowEngine] Extracted {} patient IDs from step 1", patientIds.size());

        if (patientIds.isEmpty()) {
            return okResult(plan, cohortStep, detailStep,
                    cohortResp.getAnswer() + "\n（未找到符合条件的患者，无法继续后续查询。）");
        }

        cohortStep.setExtractedValues(patientIds);

        // Step 2: DETAIL
        log.info("[WorkflowEngine] Step 2 (DETAIL): {}", detailStep.getNlQuery());
        detailStep.setStatus(WorkflowStep.Status.RUNNING);
        detailStep.setInjectedValues(patientIds);

        EsMindCompiler.QueryResponse detailResp = compiler.compile(detailStep.getNlQuery());
        if (detailResp.getError() != null) {
            detailStep.setError(detailResp.getError());
            detailStep.setStatus(WorkflowStep.Status.FAILED);
            return failResult(plan, "第二步（明细查询）失败：" + detailResp.getError());
        }

        // Inject patient_id filter into DSL
        String modifiedDsl = injectPatientIdFilter(detailResp.getDsl(), patientIds);
        if (modifiedDsl == null) modifiedDsl = detailResp.getDsl();

        // Execute modified DSL
        String esResult = esClient.search(indexName, modifiedDsl);
        detailStep.setEsRawResult(esResult);
        detailStep.setDsl(modifiedDsl);

        // Transform result
        detailStep.setResultText(transformEsResult(esResult));
        detailStep.setStatus(WorkflowStep.Status.COMPLETED);

        // Combine
        String combined = combinePivotResult(cohortStep, detailStep, patientIds);
        return okResult(plan, cohortStep, detailStep, combined);
    }

    // ========================================================================
    // TREND_COMPARE Executor
    // ========================================================================

    private WorkflowResult executeTrendCompare(WorkflowPlan plan) throws Exception {
        WorkflowStep stepA = plan.getStep(0);
        WorkflowStep stepB = plan.getStep(1);
        String labelA = plan.getTimeRangeALabel();
        String labelB = plan.getTimeRangeBLabel();

        log.info("[WorkflowEngine] TREND_COMPARE: {} vs {}", labelA, labelB);

        // Step A: 第一次时间范围
        stepA.setStatus(WorkflowStep.Status.RUNNING);
        String queryA = buildTimeQuery(plan.getOriginalQuery(), labelA, labelB, true);
        EsMindCompiler.QueryResponse respA = compiler.compile(queryA);
        stepA.setEsRawResult(respA.getEsRawResult());
        stepA.setDsl(respA.getDsl());
        stepA.setAggregationResult(respA.getEsRawResult());
        stepA.setResultText(respA.getAnswer());
        stepA.setStatus(WorkflowStep.Status.COMPLETED);

        // Step B: 第二次时间范围
        stepB.setStatus(WorkflowStep.Status.RUNNING);
        String queryB = buildTimeQuery(plan.getOriginalQuery(), labelA, labelB, false);
        EsMindCompiler.QueryResponse respB = compiler.compile(queryB);
        stepB.setEsRawResult(respB.getEsRawResult());
        stepB.setDsl(respB.getDsl());
        stepB.setAggregationResult(respB.getEsRawResult());
        stepB.setResultText(respB.getAnswer());
        stepB.setStatus(WorkflowStep.Status.COMPLETED);

        // Compare & format
        String compared = formatComparison(
                labelA, respA.getEsRawResult(),
                labelB, respB.getEsRawResult());
        return okResult(plan, stepA, stepB, compared);
    }

    /**
     * 构建时间范围查询：将原查询中的时间词替换为具体时间范围。
     */
    private String buildTimeQuery(String original, String labelA, String labelB, boolean isFirst) {
        LocalDate now = LocalDate.now();
        String targetLabel = isFirst ? labelA : labelB;

        // 当月/本月
        String currentMonth = now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        // 上月
        String lastMonth = now.minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        // 去年
        String lastYear = String.valueOf(now.getYear() - 1);

        String query = original;

        // 替换时间标签为具体日期
        query = query.replace(targetLabel, isFirst
                ? lastMonth + "（" + currentMonth + "）"
                : currentMonth);

        // 处理 vs/对比 关键词，保留主要查询意图
        query = query.replaceAll("(vs|VS|对比|versus|比较).*", "").trim();

        return query;
    }

    /**
     * 格式化对比结果表格。
     */
    private String formatComparison(String labelA, String esJsonA,
                                     String labelB, String esJsonB) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 趋势对比：").append(labelA).append(" vs ").append(labelB).append("\n\n");

        try {
            long countA = extractTotalHits(esJsonA);
            long countB = extractTotalHits(esJsonB);

            sb.append("| 指标 | ").append(labelA).append(" | ").append(labelB)
                    .append(" | 变化 |\n");
            sb.append("|------|--------|--------|------|\n");
            sb.append("| 记录数 | ").append(countA).append(" | ")
                    .append(countB).append(" | ");

            if (countA > 0) {
                double pctChange = ((double)(countB - countA) / countA) * 100;
                String arrow = pctChange > 0 ? "↑" : (pctChange < 0 ? "↓" : "→");
                sb.append(String.format("%s %.1f%%", arrow, Math.abs(pctChange)));
            } else {
                sb.append("N/A");
            }
            sb.append(" |\n");

            // 尝试提取聚合数据（如果存在 date_histogram 聚合）
            try {
                JsonNode rootA = MAPPER.readTree(esJsonA);
                JsonNode rootB = MAPPER.readTree(esJsonB);

                JsonNode aggsA = rootA.get("aggregations");
                JsonNode aggsB = rootB.get("aggregations");

                if (aggsA != null && aggsB != null && aggsA.fieldNames().hasNext()) {
                    String aggName = aggsA.fieldNames().next();
                    JsonNode bucketsA = findBuckets(aggsA.get(aggName));
                    JsonNode bucketsB = findBuckets(aggsB.get(aggName));

                    if (bucketsA != null && bucketsA.isArray()
                            && bucketsB != null && bucketsB.isArray()) {
                        sb.append("\n| 时间段 | ").append(labelA)
                                .append(" | ").append(labelB).append(" |\n");
                        sb.append("|--------|--------|--------|\n");

                        Set<String> allKeys = new LinkedHashSet<>();
                        for (JsonNode b : bucketsA) {
                            allKeys.add(b.get("key_as_string").asText());
                        }
                        for (JsonNode b : bucketsB) {
                            allKeys.add(b.get("key_as_string").asText());
                        }

                        for (String key : allKeys) {
                            long valA = findBucketCount(bucketsA, key);
                            long valB = findBucketCount(bucketsB, key);
                            sb.append("| ").append(key).append(" | ")
                                    .append(valA).append(" | ").append(valB).append(" |\n");
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[WorkflowEngine] Failed to format comparison buckets: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.warn("[WorkflowEngine] Failed to format comparison: {}", e.getMessage());
            sb.append("对比结果解析失败。");
        }

        return sb.toString();
    }

    private long extractTotalHits(String esJson) throws Exception {
        JsonNode root = MAPPER.readTree(esJson);
        JsonNode total = root.get("hits").get("total");
        return total.isObject() ? total.get("value").asLong() : total.asLong();
    }

    private JsonNode findBuckets(JsonNode aggNode) {
        JsonNode buckets = aggNode.get("buckets");
        if (buckets != null) return buckets;
        // nested aggregation
        Iterator<String> fields = aggNode.fieldNames();
        while (fields.hasNext()) {
            String fn = fields.next();
            if (fn.startsWith("by_")) {
                JsonNode sub = aggNode.get(fn);
                if (sub != null && sub.has("buckets")) {
                    return sub.get("buckets");
                }
            }
        }
        return null;
    }

    private long findBucketCount(JsonNode buckets, String key) {
        if (buckets == null || !buckets.isArray()) return 0;
        for (JsonNode b : buckets) {
            String k = b.has("key_as_string") ? b.get("key_as_string").asText()
                    : String.valueOf(b.get("key").asLong());
            if (k.equals(key)) return b.get("doc_count").asLong();
        }
        return 0;
    }

    // ========================================================================
    // MULTI_CHAIN Executor
    // ========================================================================

    private WorkflowResult executeMultiChain(WorkflowPlan plan) throws Exception {
        // 当前 MULTI_CHAIN 作为扩展的 PIVOT 处理（支持 3+ 步骤）
        log.info("[WorkflowEngine] MULTI_CHAIN: {} steps", plan.stepCount());

        List<String> currentPatientIds = Collections.emptyList();
        String lastResultText = "";

        for (int i = 0; i < plan.stepCount(); i++) {
            WorkflowStep step = plan.getStep(i);
            log.info("[WorkflowEngine] Chain step {}: {}", i, step.getNlQuery());
            step.setStatus(WorkflowStep.Status.RUNNING);

            // 编译
            EsMindCompiler.QueryResponse resp = compiler.compile(step.getNlQuery());
            step.setDsl(resp.getDsl());

            if (currentPatientIds.isEmpty()) {
                // 第一轮：直接返回
                step.setResultText(resp.getAnswer());
                step.setEsRawResult(resp.getEsRawResult());
                lastResultText = resp.getAnswer();

                // 提取 patient_ids（如果不是最后一步）
                if (i < plan.stepCount() - 1) {
                    currentPatientIds = extractPatientIds(resp.getEsRawResult());
                    step.setExtractedValues(currentPatientIds);
                    log.info("[WorkflowEngine] Chain extracted {} patient IDs", currentPatientIds.size());
                }
            } else {
                // 后续步骤：注入 patient_id 过滤
                String modifiedDsl = injectPatientIdFilter(resp.getDsl(), currentPatientIds);
                if (modifiedDsl == null) modifiedDsl = resp.getDsl();

                String esResult = esClient.search(indexName, modifiedDsl);
                step.setEsRawResult(esResult);
                step.setDsl(modifiedDsl);
                step.setInjectedValues(currentPatientIds);
                step.setResultText(transformEsResult(esResult));
                lastResultText = step.getResultText();

                // 为下一个步骤提取 patient_ids
                if (i < plan.stepCount() - 1) {
                    currentPatientIds = extractPatientIds(esResult);
                    step.setExtractedValues(currentPatientIds);
                    log.info("[WorkflowEngine] Chain step {} extracted {} patient IDs",
                            i, currentPatientIds.size());
                }
            }

            step.setStatus(WorkflowStep.Status.COMPLETED);
        }

        return new WorkflowResult(plan.getOriginalQuery(), plan.getSteps(),
                lastResultText, null, 0);
    }

    // ========================================================================
    // Common: patient_id extraction from ES response
    // ========================================================================

    List<String> extractPatientIds(String esRawResult) {
        if (esRawResult == null || esRawResult.isEmpty()) return Collections.emptyList();

        Set<String> ids = new LinkedHashSet<>();
        try {
            JsonNode root = MAPPER.readTree(esRawResult);
            JsonNode hits = root.get("hits");
            if (hits == null) return Collections.emptyList();

            JsonNode hitArray = hits.get("hits");
            if (hitArray == null || !hitArray.isArray()) return Collections.emptyList();

            for (JsonNode hit : hitArray) {
                // 1. inner_hits (nested 表数据)
                JsonNode innerHits = hit.get("inner_hits");
                if (innerHits != null) {
                    extractFromInnerHits(innerHits, ids);
                }

                // 2. _source
                JsonNode source = hit.get("_source");
                if (source != null) {
                    extractFromSource(source, ids);
                }
            }
        } catch (Exception e) {
            log.warn("[WorkflowEngine] Failed to parse ES result for patient_ids: {}", e.getMessage());
        }

        List<String> result = new ArrayList<>(ids);
        return result.size() > 100 ? result.subList(0, 100) : result;
    }

    private void extractFromInnerHits(JsonNode innerHits, Set<String> ids) {
        Iterator<Map.Entry<String, JsonNode>> ihFields = innerHits.fields();
        while (ihFields.hasNext()) {
            Map.Entry<String, JsonNode> entry = ihFields.next();
            JsonNode ihData = entry.getValue();
            JsonNode ihHits = ihData.get("hits");
            if (ihHits == null) continue;
            JsonNode ihHitsArray = ihHits.get("hits");
            if (ihHitsArray == null || !ihHitsArray.isArray()) continue;

            for (JsonNode ihHit : ihHitsArray) {
                JsonNode ihSource = ihHit.get("_source");
                if (ihSource != null) {
                    JsonNode pid = ihSource.get("patient_id");
                    if (pid != null && pid.isTextual() && !pid.asText().isEmpty()) {
                        ids.add(pid.asText().trim());
                    }
                }
            }
        }
    }

    private void extractFromSource(JsonNode source, Set<String> ids) {
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (value.isArray()) {
                for (JsonNode item : value) {
                    if (item.isObject()) {
                        JsonNode pid = item.get("patient_id");
                        if (pid != null && pid.isTextual() && !pid.asText().isEmpty()) {
                            ids.add(pid.asText().trim());
                        }
                    }
                }
            } else if (value.isObject()) {
                JsonNode pid = value.get("patient_id");
                if (pid != null && pid.isTextual() && !pid.asText().isEmpty()) {
                    ids.add(pid.asText().trim());
                }
            }
        }
    }

    // ========================================================================
    // Common: DSL injection
    // ========================================================================

    String injectPatientIdFilter(String dsl, List<String> patientIds) {
        if (dsl == null || patientIds == null || patientIds.isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(dsl);
            if (!(root instanceof ObjectNode)) return null;
            ObjectNode dslObj = (ObjectNode) root;

            ObjectNode termsQuery = MAPPER.createObjectNode();
            ObjectNode termsBody = MAPPER.createObjectNode();
            ArrayNode values = termsBody.putArray(ROOT_PATIENT_ID_FIELD);
            for (String pid : patientIds) values.add(pid);
            termsQuery.set("terms", termsBody);

            JsonNode queryNode = dslObj.get("query");
            if (queryNode != null && queryNode.has("bool")) {
                ObjectNode boolNode = (ObjectNode) queryNode.get("bool");
                JsonNode existingFilter = boolNode.get("filter");
                if (existingFilter != null && existingFilter.isArray()) {
                    ((ArrayNode) existingFilter).add(termsQuery);
                } else {
                    ArrayNode filter = MAPPER.createArrayNode();
                    filter.add(termsQuery);
                    boolNode.set("filter", filter);
                }
            } else if (queryNode != null) {
                ObjectNode wrapper = MAPPER.createObjectNode();
                ArrayNode filter = MAPPER.createArrayNode();
                filter.add(termsQuery);
                wrapper.set("must", MAPPER.createArrayNode().add(queryNode));
                wrapper.set("filter", filter);
                dslObj.set("query", wrapper);
            } else {
                return null;
            }

            return MAPPER.writeValueAsString(dslObj);
        } catch (Exception e) {
            log.warn("[WorkflowEngine] Failed to inject patient IDs: {}", e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // Common: result transformation
    // ========================================================================

    private String transformEsResult(String esResult) {
        try {
            io.esmind.renderer.ResultTransformer transformer =
                    new io.esmind.renderer.ResultTransformer();
            io.esmind.renderer.ResultTransformer.TransformResult tr =
                    transformer.transform(esResult, null);
            String result = tr.getSummary();
            if (tr.getMarkdownTable() != null) {
                result += "\n\n" + tr.getMarkdownTable();
            }
            return result;
        } catch (Exception e) {
            log.warn("[WorkflowEngine] Transform failed: {}", e.getMessage());
            try {
                JsonNode root = MAPPER.readTree(esResult);
                JsonNode total = root.get("hits").get("total");
                long count = total.isObject() ? total.get("value").asLong() : total.asLong();
                return "共查询到 **" + count + "** 条结果。";
            } catch (Exception e2) {
                return "(无法解析结果)";
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String extractTimeLabel(String segment) {
        if (segment.contains("上月") || segment.contains("过去一个月")) return "上月";
        if (segment.contains("本月") || segment.contains("这个月")) return "本月";
        if (segment.contains("去年")) return "去年";
        if (segment.contains("今年")) return "今年";
        if (segment.contains("上周")) return "上周";
        if (segment.contains("本周")) return "本周";
        // 默认用原始段
        return segment.length() > 10 ? segment.substring(0, 10) + "..." : segment;
    }

    private String combinePivotResult(WorkflowStep cohort, WorkflowStep detail, List<String> patientIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 第一步：患者筛选\n");
        sb.append(cohort.getResultText() != null ? cohort.getResultText() : "(无结果)");
        sb.append("\n\n共筛选出 **").append(patientIds.size()).append("** 名患者，患者ID（前20个）：\n\n");
        int count = 0;
        for (String pid : patientIds) {
            if (count >= 20) break;
            sb.append(++count).append(". ").append(pid).append("\n");
        }
        if (patientIds.size() > 20) sb.append("... 共 ").append(patientIds.size()).append(" 名\n");
        sb.append("\n### 第二步：上述患者的查询结果\n");
        sb.append(detail.getResultText() != null ? detail.getResultText() : "(无结果)");
        return sb.toString();
    }

    private WorkflowResult okResult(WorkflowPlan plan, WorkflowStep a, WorkflowStep b, String answer) {
        return new WorkflowResult(plan.getOriginalQuery(), List.of(a, b), answer, null, 0);
    }

    private WorkflowResult failResult(WorkflowPlan plan, String error) {
        return new WorkflowResult(plan.getOriginalQuery(), plan.getSteps(), error, null, 0);
    }

    // ========================================================================
    // Result DTO
    // ========================================================================

    public static class WorkflowResult {
        private final String originalQuery;
        private final List<WorkflowStep> steps;
        private final String answer;
        private final String esRawResult;
        private long elapsedMs;
        private String error;

        public WorkflowResult(String originalQuery, List<WorkflowStep> steps,
                              String answer, String esRawResult, long elapsedMs) {
            this.originalQuery = originalQuery;
            this.steps = steps;
            this.answer = answer;
            this.esRawResult = esRawResult;
            this.elapsedMs = elapsedMs;
        }

        public String getOriginalQuery() { return originalQuery; }
        public List<WorkflowStep> getSteps() { return steps; }
        public boolean hasSteps() { return steps != null && !steps.isEmpty(); }
        public String getAnswer() { return answer; }
        public String getEsRawResult() { return esRawResult; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long ms) { this.elapsedMs = ms; }
        public String getError() { return error; }
        public void setError(String err) { this.error = err; }
        public boolean hasError() { return error != null; }
    }
}
