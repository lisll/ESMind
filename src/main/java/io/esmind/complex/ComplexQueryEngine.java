package io.esmind.complex;

import io.esmind.compiler.EsRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Complex Query Engine — 复杂查询引擎（科研取数专用）。
 * <p>
 * 核心职责：按声明式步骤执行多步数据提取，支持条件分支和数据依赖。
 */
public class ComplexQueryEngine {

    private static final Logger log = LoggerFactory.getLogger(ComplexQueryEngine.class);

    private final StepExecutor stepExecutor;
    private final ConditionEngine conditionEngine;

    public ComplexQueryEngine(EsRestClient esRestClient, String indexName) {
        this.stepExecutor = new StepExecutor(esRestClient, indexName);
        this.conditionEngine = new ConditionEngine();
    }

    /**
     * 执行提取任务。
     *
     * @param def 提取定义
     * @return 完整结果
     */
    public ExtractResult execute(ExtractDef def) {
        log.info("ComplexQueryEngine: executing '{}' v{}", def.getResearchName(), def.getVersion());

        ExtractResult result = new ExtractResult(def.getResearchName(), def.getVersion());
        Map<String, Object> context = new HashMap<>();

        // 初始化 context（sources）
        context.putAll(def.getSources());

        // 执行步骤（当前简单按顺序，后续可扩展拓扑排序）
        for (ExtractStep step : def.getSteps()) {
            log.info("ComplexQueryEngine: processing step '{}'", step.getId());

            // 检查依赖是否满足
            if (step.getDependsOn() != null) {
                for (String depId : step.getDependsOn()) {
                    if (!context.containsKey(depId)) {
                        log.error("ComplexQueryEngine: missing dependency '{}' for step '{}'", depId, step.getId());
                        result.addError("Missing dependency '" + depId + "' for step '" + step.getId() + "'");
                        return result;
                    }
                }
            }

            // 执行步骤
            StepExecutor.StepResult stepResult = stepExecutor.execute(step, context);

            // 应用策略
            if (stepResult.isSuccess()) {
                stepResult = conditionEngine.applyStrategy(step, stepResult);
            }

            // 保存结果到 context 和总结果
            context.put(step.getId(), stepResult);
            result.addStepResult(stepResult);
        }

        log.info("ComplexQueryEngine: execution complete, {} steps, {} errors",
                result.getStepResults().size(), result.getErrors().size());

        return result;
    }

    // --- 提取结果类 ---

    public static class ExtractResult {
        private final String researchName;
        private final String version;
        private final List<StepExecutor.StepResult> stepResults;
        private final List<String> errors;

        public ExtractResult(String researchName, String version) {
            this.researchName = researchName;
            this.version = version;
            this.stepResults = new ArrayList<>();
            this.errors = new ArrayList<>();
        }

        public String getResearchName() { return researchName; }
        public String getVersion() { return version; }
        public List<StepExecutor.StepResult> getStepResults() { return stepResults; }
        public List<String> getErrors() { return errors; }

        public void addStepResult(StepExecutor.StepResult result) {
            this.stepResults.add(result);
            if (!result.isSuccess() && result.getError() != null) {
                this.errors.add(result.getError());
            }
        }

        public void addError(String error) {
            this.errors.add(error);
        }

        public boolean isSuccess() {
            return errors.isEmpty();
        }

        /**
         * 获取指定步骤的结果。
         */
        public StepExecutor.StepResult getStepResult(String stepId) {
            return stepResults.stream()
                    .filter(r -> stepId.equals(r.getStepId()))
                    .findFirst()
                    .orElse(null);
        }
    }
}
