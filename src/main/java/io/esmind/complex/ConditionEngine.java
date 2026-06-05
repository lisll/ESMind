package io.esmind.complex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 条件引擎：处理 if_else、threshold、exists 等条件判断。
 */
public class ConditionEngine {

    private static final Logger log = LoggerFactory.getLogger(ConditionEngine.class);

    /**
     * 应用策略到步骤结果。
     *
     * @param step 步骤定义
     * @param result 原始执行结果
     * @return 应用策略后的结果
     */
    public StepExecutor.StepResult applyStrategy(ExtractStep step, StepExecutor.StepResult result) {
        if (step.getStrategy() == null || "simple".equals(step.getStrategy().getType())) {
            return result; // 无策略或简单策略，直接返回
        }

        log.info("ConditionEngine: applying strategy '{}' to step '{}'",
                step.getStrategy().getType(), step.getId());

        return switch (step.getStrategy().getType()) {
            case "if_else" -> applyIfElse(step, result);
            case "threshold" -> applyThreshold(step, result);
            default -> result;
        };
    }

    /**
     * 应用 if_else 策略。
     * 示例：如果有异常体温 (>37.3)，只取异常；否则取全部。
     */
    private StepExecutor.StepResult applyIfElse(ExtractStep step, StepExecutor.StepResult result) {
        List<Map<String, Object>> hits = result.getHits();
        if (hits.isEmpty()) return result;

        // 检测是否有异常体温
        boolean hasAbnormal = false;
        for (Map<String, Object> hit : hits) {
            Object value = hit.get("vital_sign_value");
            if (value instanceof Number num && num.doubleValue() > 37.3) {
                hasAbnormal = true;
                break;
            }
        }

        if (hasAbnormal) {
            log.info("ConditionEngine: if_else condition met for step '{}' — filtering abnormal", step.getId());
            StepExecutor.StepResult filtered = new StepExecutor.StepResult(step.getId());
            for (Map<String, Object> hit : hits) {
                Object value = hit.get("vital_sign_value");
                if (value instanceof Number num && num.doubleValue() > 37.3) {
                    filtered.getHits().add(hit);
                }
            }
            return filtered;
        } else {
            log.info("ConditionEngine: if_else condition not met for step '{}' — returning all", step.getId());
            return result;
        }
    }

    /**
     * 应用 threshold 策略。
     */
    private StepExecutor.StepResult applyThreshold(ExtractStep step, StepExecutor.StepResult result) {
        // 当前实现占位，扩展时补充
        return result;
    }
}
