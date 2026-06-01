package io.esmind.workflow;

import java.util.*;

/**
 * WorkflowPlan — 多步工作流的完整计划。
 * <p>
 * 由 PatternDetector 创建，由 WorkflowEngine 执行。
 * <p>
 * 三种模式：
 * <ol>
 *   <li>PIVOT: COHORT → DETAIL（患者队列→跨表明细）</li>
 *   <li>MULTI_CHAIN: 多步收窄（逐步过滤）</li>
 *   <li>TREND_COMPARE: COMPARE_A → COMPARE_B → 对比输出</li>
 * </ol>
 */
public class WorkflowPlan {

    private final Type type;
    private final String originalQuery;
    private final List<WorkflowStep> steps;
    private final String description;

    /** TREND_COMPARE 专用：时间范围 A 的描述 */
    private String timeRangeALabel;
    /** TREND_COMPARE 专用：时间范围 B 的描述 */
    private String timeRangeBLabel;
    /** TREND_COMPARE 专用：聚合查询类型 */
    private String aggregationType;

    private Status status;
    private String combinedAnswer;
    private String error;
    private long totalElapsedMs;

    public enum Type {
        /** 患者队列→跨表 pivot 查询 */
        PIVOT,
        /** 多表条件链 */
        MULTI_CHAIN,
        /** 趋势对比 */
        TREND_COMPARE
    }

    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public WorkflowPlan(Type type, String originalQuery, String description) {
        this.type = type;
        this.originalQuery = originalQuery;
        this.description = description;
        this.steps = new ArrayList<>();
        this.status = Status.PENDING;
    }

    // ========================================================================
    // Step management
    // ========================================================================

    public WorkflowPlan addStep(WorkflowStep step) {
        this.steps.add(step);
        return this;
    }

    public WorkflowStep getStep(int index) {
        return index >= 0 && index < steps.size() ? steps.get(index) : null;
    }

    public WorkflowStep getStep(String id) {
        return steps.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(null);
    }

    public int stepCount() { return steps.size(); }

    public boolean allCompleted() {
        return steps.stream().allMatch(WorkflowStep::isCompleted);
    }

    public boolean anyFailed() {
        return steps.stream().anyMatch(WorkflowStep::isFailed);
    }

    public String getFirstError() {
        return steps.stream()
                .filter(WorkflowStep::isFailed)
                .map(WorkflowStep::getError)
                .filter(e -> e != null)
                .findFirst()
                .orElse(null);
    }

    // ========================================================================
    // Getters & Setters
    // ========================================================================

    public Type getType() { return type; }
    public String getOriginalQuery() { return originalQuery; }
    public List<WorkflowStep> getSteps() { return Collections.unmodifiableList(steps); }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public String getCombinedAnswer() { return combinedAnswer; }
    public void setCombinedAnswer(String answer) { this.combinedAnswer = answer; }
    public String getError() { return error; }
    public void setError(String err) { this.error = err; }
    public long getTotalElapsedMs() { return totalElapsedMs; }
    public void setTotalElapsedMs(long ms) { this.totalElapsedMs = ms; }

    public String getTimeRangeALabel() { return timeRangeALabel; }
    public void setTimeRangeALabel(String label) { this.timeRangeALabel = label; }
    public String getTimeRangeBLabel() { return timeRangeBLabel; }
    public void setTimeRangeBLabel(String label) { this.timeRangeBLabel = label; }
    public String getAggregationType() { return aggregationType; }
    public void setAggregationType(String t) { this.aggregationType = t; }

    @Override
    public String toString() {
        return String.format("Workflow[%s|%s] %s (%d steps)",
                type, status, description, steps.size());
    }
}
