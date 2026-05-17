package io.esmind.semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义 IR — LLM 输出的纯语义查询意图。
 * 不包含任何 ES/DSL 概念。所有 field/nested/path 由下游组件决定。
 *
 * <p>LLM 只负责填充这个结构，不接触 ES DSL。</p>
 */
public class SemanticIR {

    private String intent;
    private List<Entity> entities;
    private TimeConstraint timeConstraint;
    private Aggregation aggregation;
    private int limit = 20;

    public SemanticIR() {
        this.entities = new ArrayList<>();
    }

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public List<Entity> getEntities() { return entities; }
    public void setEntities(List<Entity> entities) { this.entities = entities; }
    public void addEntity(Entity e) { this.entities.add(e); }

    public TimeConstraint getTimeConstraint() { return timeConstraint; }
    public void setTimeConstraint(TimeConstraint tc) { this.timeConstraint = tc; }

    public Aggregation getAggregation() { return aggregation; }
    public void setAggregation(Aggregation agg) { this.aggregation = agg; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }

    // ===== Entity =====

    public static class Entity {
        private String type;    // disease, symptom, department, lab_item, patient_id, medicine, surgery, exam_item
        private String value;   // 原始值
        private String operator; // gt, gte, lt, lte, eq (for lab values)
        private String numericValue; // lab value numeric comparison target

        public Entity() {}

        public Entity(String type, String value) {
            this.type = type;
            this.value = value;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }

        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }

        public String getNumericValue() { return numericValue; }
        public void setNumericValue(String numericValue) { this.numericValue = numericValue; }
    }

    // ===== TimeConstraint =====

    public static class TimeConstraint {
        private String type;      // "relative" | "absolute"
        private int value;
        private String unit;      // "day", "month", "year"
        private String startDate; // for absolute
        private String endDate;   // for absolute

        public TimeConstraint() {}

        public TimeConstraint(String type, int value, String unit) {
            this.type = type;
            this.value = value;
            this.unit = unit;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }

        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }

        public String getStartDate() { return startDate; }
        public void setStartDate(String d) { this.startDate = d; }

        public String getEndDate() { return endDate; }
        public void setEndDate(String d) { this.endDate = d; }
    }

    // ===== Aggregation =====

    public static class Aggregation {
        private String type;   // "count" | "group_by"
        private String field;  // semantic field name for grouping

        public Aggregation() {}

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
    }
}
