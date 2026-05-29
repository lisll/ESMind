package io.esmind.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * SemanticIR — 系统唯一内部查询语言。
 * <p>
 * 不变原则：
 * <ol>
 *   <li>字段一旦定义不再增删（只扩展枚举值）</li>
 *   <li>ASTBuilder 只依赖此结构的字段做纯机械转换</li>
 *   <li>FastPath 和 LLM Path 都输出相同结构</li>
 *   <li>version 字段标识 Schema 版本，用于缓存校验</li>
 * </ol>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SemanticIR {

    /** IR schema 版本号。Entity 编译字段变更时 +1 */
    public static final int CURRENT_VERSION = 2;

    private int version = CURRENT_VERSION;
    private String intent;
    private List<Entity> entities;
    private Aggregation aggregation;
    private int limit = 20;

    public SemanticIR() {
        this.entities = new ArrayList<>();
    }

    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public List<Entity> getEntities() { return entities; }
    public void setEntities(List<Entity> entities) { this.entities = entities; }
    public void addEntity(Entity e) { this.entities.add(e); }

    public Aggregation getAggregation() { return aggregation; }
    public void setAggregation(Aggregation agg) { this.aggregation = agg; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }

    // ========================================================================
    // Entity — 原子查询条件
    // ========================================================================
    // 语义字段（所有解析器都必须输出）：
    //   type, value, operator, numericValue
    // 编译字段（Resolution 阶段填充，ASTBuilder 直接使用）：
    //   clauseType, table, field, keyword, valueField, useSynonyms, unit
    // 时间条件也使用此结构：type="time", clauseType="range"
    // ========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entity {

        // ── 语义层 ──
        private String type;            // disease | lab_item | medicine | surgery | department
                                        // | patient_id | report_type | exam_item | time
        private String value;           // 原始值："高血压", "白细胞", "30"
        private String unit;            // 时间单位："day", "month", "year"（仅 type=time）
        private String timeType;        // 时间类型："RELATIVE" | "ABSOLUTE"（仅 type=time）
                                        // RELATIVE: value=天数，unit=day/month/year
                                        // ABSOLUTE: value=yyyy-MM-dd 或 yyyy-MM 或 yyyy
        private String operator;        // gt | gte | lt | lte | eq（仅数值比较）
        private String numericValue;    // 比较目标值

        // ── 编译层（Resolution 填充） ──
        private String clauseType;      // term | match_phrase | exists | range（查询子句类型）
        private String context;          // "ROOT" | "NESTED"（查询上下文）
        private String contextPath;      // nested 路径（仅 context=NESTED）
        private String table;           // 业务表名（nested 表路径 = null 为顶级）
        private String field;           // ES 字段路径
        private String keyword;         // keyword 子字段（精确匹配用）
        private String valueField;      // 数值字段（如检验结果值）
        private boolean useSynonyms;    // 是否启用同义词
        private String group;           // 多表 should 组合分组标识

        public Entity() {}

        public Entity(String type, String value) {
            this.type = type;
            this.value = value;
        }

        public String getType() { return type; }
        public void setType(String t) { this.type = t; }

        public String getValue() { return value; }
        public void setValue(String v) { this.value = v; }

        public String getUnit() { return unit; }
        public void setUnit(String u) { this.unit = u; }

        public String getTimeType() { return timeType; }
        public void setTimeType(String tt) { this.timeType = tt; }

        public String getOperator() { return operator; }
        public void setOperator(String op) { this.operator = op; }

        public String getNumericValue() { return numericValue; }
        public void setNumericValue(String nv) { this.numericValue = nv; }

        public String getClauseType() { return clauseType; }
        public void setClauseType(String ct) { this.clauseType = ct; }

        public String getContext() { return context; }
        public void setContext(String c) { this.context = c; }

        public String getContextPath() { return contextPath; }
        public void setContextPath(String cp) { this.contextPath = cp; }

        public String getTable() { return table; }
        public void setTable(String t) { this.table = t; }

        public String getField() { return field; }
        public void setField(String f) { this.field = f; }

        public String getKeyword() { return keyword; }
        public void setKeyword(String k) { this.keyword = k; }

        public String getValueField() { return valueField; }
        public void setValueField(String vf) { this.valueField = vf; }

        public boolean isUseSynonyms() { return useSynonyms; }
        public void setUseSynonyms(boolean us) { this.useSynonyms = us; }

        public String getGroup() { return group; }
        public void setGroup(String g) { this.group = g; }
    }

    // ========================================================================
    // Aggregation
    // ========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Aggregation {
        private String type;       // "count" | "date_histogram" | "terms"
        private String field;      // ES 字段路径（date_histogram/terms 需要完整路径如 "menzhenjiuzhenjilu.visit_time"）
        private String interval;   // "month" | "day" | "quarter" | "year"（仅 date_histogram）
        private String format;     // "yyyy-MM" 等日期格式（仅 date_histogram）
        private int size = 10;     // 最大返回桶数
        private String name;       // 聚合名称

        public Aggregation() {}

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getInterval() { return interval; }
        public void setInterval(String interval) { this.interval = interval; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
