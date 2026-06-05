package io.esmind.compiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Schema 质量注册表：记录字段的统计信息、使用频率、填充率等。
 * <p>
 * 用于：
 * - 推荐填充率高的字段
 * - 检测字段名冲突
 * - 记录字段使用历史
 */
public class SchemaQualityRegistry {

    private static final Logger log = LoggerFactory.getLogger(SchemaQualityRegistry.class);

    private final Map<String, FieldQuality> fieldQualities;

    public SchemaQualityRegistry() {
        this.fieldQualities = new HashMap<>();
    }

    /**
     * 记录字段信息。
     */
    public void recordField(String table, String field, double fillRate, long useCount) {
        String key = table + "." + field;
        FieldQuality quality = fieldQualities.computeIfAbsent(key, k -> new FieldQuality(table, field));
        quality.setFillRate(fillRate);
        quality.setUseCount(quality.getUseCount() + useCount);
        log.debug("SchemaQualityRegistry: recorded {}.{} - fillRate={}, useCount={}",
                table, field, fillRate, quality.getUseCount());
    }

    /**
     * 获取字段质量信息。
     */
    public FieldQuality getFieldQuality(String table, String field) {
        return fieldQualities.get(table + "." + field);
    }

    /**
     * 为指定表选择填充率最高的字段。
     */
    public String selectBestField(String table, String... candidates) {
        String bestField = null;
        double bestRate = -1;

        for (String candidate : candidates) {
            FieldQuality quality = getFieldQuality(table, candidate);
            if (quality != null && quality.getFillRate() > bestRate) {
                bestRate = quality.getFillRate();
                bestField = candidate;
            }
        }

        if (bestField != null) {
            log.info("SchemaQualityRegistry: selected best field {}.{} with fillRate={}",
                    table, bestField, bestRate);
        }

        return bestField;
    }

    // --- 内部类 ---

    public static class FieldQuality {
        private final String table;
        private final String field;
        private double fillRate; // 0.0 ~ 1.0
        private long useCount;
        private long lastUsedTimestamp;

        public FieldQuality(String table, String field) {
            this.table = table;
            this.field = field;
            this.lastUsedTimestamp = System.currentTimeMillis();
        }

        public String getTable() { return table; }
        public String getField() { return field; }

        public double getFillRate() { return fillRate; }
        public void setFillRate(double fillRate) { this.fillRate = fillRate; }

        public long getUseCount() { return useCount; }
        public void setUseCount(long useCount) { this.useCount = useCount; }

        public long getLastUsedTimestamp() { return lastUsedTimestamp; }
        public void setLastUsedTimestamp(long lastUsedTimestamp) { this.lastUsedTimestamp = lastUsedTimestamp; }
    }
}
