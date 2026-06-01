package io.esmind.workflow;

import io.esmind.semantic.SemanticIR;

import java.util.*;

/**
 * WorkflowStep — 工作流中的一个执行步骤。
 */
public class WorkflowStep {

    private final String id;
    private final Type type;
    private final String description;
    private SemanticIR ir;
    private String nlQuery;
    private List<String> dependsOn;
    private String extractField;
    private List<String> extractedValues;
    private Status status;
    private List<String> injectedValues;
    private String injectField;
    private String resultText;
    private String esRawResult;
    private String dsl;
    private String error;

    /** TREND_COMPARE 专用：该步骤的聚合结果JSON */
    private String aggregationResult;

    /** TREND_COMPARE 专用：时间范围标签 */
    private String timeRangeLabel;

    public enum Type {
        /** 患者队列查询 — 找出符合条件的患者 ID */
        COHORT,
        /** 患者明细查询 — 用患者 ID 查具体记录 */
        DETAIL,
        /** 聚合比较 — 两次聚合结果对比 */
        COMPARE,
        /** 条件过滤 — 在上一步结果基础上收窄 */
        FILTER
    }

    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public WorkflowStep(String id, Type type, String description) {
        this.id = id;
        this.type = type;
        this.description = description;
        this.dependsOn = new ArrayList<>();
        this.extractedValues = new ArrayList<>();
        this.injectedValues = new ArrayList<>();
        this.status = Status.PENDING;
    }

    // ========================================================================
    // Fluent builders
    // ========================================================================

    public WorkflowStep withIr(SemanticIR ir) {
        this.ir = ir;
        return this;
    }

    public WorkflowStep withNlQuery(String nlQuery) {
        this.nlQuery = nlQuery;
        return this;
    }

    public WorkflowStep withDependsOn(String... stepIds) {
        this.dependsOn = Arrays.asList(stepIds);
        return this;
    }

    public WorkflowStep withExtractField(String field) {
        this.extractField = field;
        return this;
    }

    public WorkflowStep withInjectField(String field) {
        this.injectField = field;
        return this;
    }

    public WorkflowStep withInjectedValues(List<String> values) {
        this.injectedValues = values != null ? values : new ArrayList<>();
        return this;
    }

    public WorkflowStep withTimeRangeLabel(String label) {
        this.timeRangeLabel = label;
        return this;
    }

    // ========================================================================
    // Getters & Setters
    // ========================================================================

    public String getId() { return id; }
    public Type getType() { return type; }
    public String getDescription() { return description; }
    public SemanticIR getIr() { return ir; }
    public void setIr(SemanticIR ir) { this.ir = ir; }
    public String getNlQuery() { return nlQuery; }
    public void setNlQuery(String nlQuery) { this.nlQuery = nlQuery; }
    public List<String> getDependsOn() { return dependsOn; }
    public String getExtractField() { return extractField; }
    public List<String> getExtractedValues() { return extractedValues; }
    public void setExtractedValues(List<String> values) { this.extractedValues = values; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public List<String> getInjectedValues() { return injectedValues; }
    public void setInjectedValues(List<String> values) { this.injectedValues = values; }
    public String getInjectField() { return injectField; }
    public String getResultText() { return resultText; }
    public void setResultText(String rt) { this.resultText = rt; }
    public String getEsRawResult() { return esRawResult; }
    public void setEsRawResult(String esr) { this.esRawResult = esr; }
    public String getDsl() { return dsl; }
    public void setDsl(String dsl) { this.dsl = dsl; }
    public String getError() { return error; }
    public void setError(String err) { this.error = err; }
    public boolean hasError() { return error != null && !error.isEmpty(); }
    public boolean isCompleted() { return status == Status.COMPLETED; }
    public boolean isFailed() { return status == Status.FAILED; }

    public String getAggregationResult() { return aggregationResult; }
    public void setAggregationResult(String json) { this.aggregationResult = json; }
    public String getTimeRangeLabel() { return timeRangeLabel; }
    public void setTimeRangeLabel(String label) { this.timeRangeLabel = label; }

    @Override
    public String toString() {
        return String.format("Step[%s|%s|%s] %s", id, type, status, description);
    }
}
