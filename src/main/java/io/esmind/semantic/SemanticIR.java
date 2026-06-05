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
    public static final int CURRENT_VERSION = 4;

    /** 查询意图枚举 */
    public enum QueryIntent {
        PATIENT_SEARCH,    // 患者搜索（默认）
        PATIENT_COUNT,     // 患者计数
        PATIENT_EXTRACT,   // 患者数据提取（单个患者多份报告）
        RESEARCH_EXTRACT,  // 科研数据提取
        AGGREGATION,       // 聚合查询
        TREND_ANALYSIS     // 趋势分析
    }

    private int version = CURRENT_VERSION;

    /** 领域分类：medical | greeting | non_medical */
    private String domain;

    private String intent;
    private List<Entity> entities;
    private Aggregation aggregation;
    private int limit = 20;

    public SemanticIR() {
        this.entities = new ArrayList<>();
    }

    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public boolean isMedical() { return "medical".equals(domain); }
    public boolean isGreeting() { return "greeting".equals(domain); }
    public boolean isNonMedical() { return "non_medical".equals(domain) || domain == null; }

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public List<Entity> getEntities() { return entities; }
    public void setEntities(List<Entity> entities) { this.entities = entities; }
    public void addEntity(Entity e) { this.entities.add(e); }

    public Aggregation getAggregation() { return aggregation; }
    public void setAggregation(Aggregation agg) { this.aggregation = agg; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }

    /** 获取查询意图枚举，如果 intent 字符串无法解析则返回 PATIENT_SEARCH */
    public QueryIntent getQueryIntent() {
        if (intent == null || intent.isBlank()) {
            return QueryIntent.PATIENT_SEARCH;
        }
        try {
            return QueryIntent.valueOf(intent.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 兼容旧的 intent 值
            if ("patient_count".equalsIgnoreCase(intent)) {
                return QueryIntent.PATIENT_COUNT;
            }
            return QueryIntent.PATIENT_SEARCH;
        }
    }

    // ========================================================================
    // Entity — 原子查询条件
    // ========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entity {

        // ── 语义层 ──
        private String type;
        private String value;
        private String unit;
        private String timeType;
        private String operator;
        private String numericValue;

        // ── 编译层（Resolution 填充） ──
        private String clauseType;
        private String context;
        private String contextPath;
        private String table;
        private String field;
        private String keyword;
        private String valueField;
        private boolean useSynonyms;
        private String group;

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
        private String type;
        private String field;
        private String interval;
        private String format;
        private int size = 10;
        private String name;
        private String nestedPath;

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
        public String getNestedPath() { return nestedPath; }
        public void setNestedPath(String np) { this.nestedPath = np; }
    }
}
