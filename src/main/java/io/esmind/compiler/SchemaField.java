package io.esmind.compiler;

import java.util.List;

/**
 * Schema 注册表中的字段元数据。
 * 每个字段对应 ES mapping 中的一个字段。
 */
public class SchemaField {

    private String fieldName;       // 完整字段路径 shouyezhenduan.diagnosis_name
    private List<String> bizNames;  // 中文业务名称
    private String esPath;          // ES 字段路径
    private String nestedPath;      // nested 表名，null=非nested
    private String type;            // nested/object/text/keyword/date/integer/long/float/double
    private String esType;
    private String keywordField;    // keyword 子字段路径
    private boolean dateField;
    private boolean numeric;
    private boolean aggregatable;

    public SchemaField() {}

    public SchemaField(String fieldName, List<String> bizNames, String esPath,
                       String nestedPath, String type, String esType,
                       String keywordField, boolean dateField,
                       boolean numeric, boolean aggregatable) {
        this.fieldName = fieldName;
        this.bizNames = bizNames;
        this.esPath = esPath;
        this.nestedPath = nestedPath;
        this.type = type;
        this.esType = esType;
        this.keywordField = keywordField;
        this.dateField = dateField;
        this.numeric = numeric;
        this.aggregatable = aggregatable;
    }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String v) { this.fieldName = v; }

    public List<String> getBizNames() { return bizNames; }
    public void setBizNames(List<String> v) { this.bizNames = v; }

    public String getEsPath() { return esPath; }
    public void setEsPath(String v) { this.esPath = v; }

    public String getNestedPath() { return nestedPath; }
    public void setNestedPath(String v) { this.nestedPath = v; }

    public String getType() { return type; }
    public void setType(String v) { this.type = v; }

    public String getEsType() { return esType; }
    public void setEsType(String v) { this.esType = v; }

    public String getKeywordField() { return keywordField; }
    public void setKeywordField(String v) { this.keywordField = v; }

    public boolean isDateField() { return dateField; }
    public void setDateField(boolean v) { this.dateField = v; }

    public boolean isNumeric() { return numeric; }
    public void setNumeric(boolean v) { this.numeric = v; }

    public boolean isAggregatable() { return aggregatable; }
    public void setAggregatable(boolean v) { this.aggregatable = v; }

    public boolean isNested() { return "nested".equals(type); }

    /** 获取 term 查询用的字段路径（优先 keyword 子字段） */
    public String getTermField() {
        return keywordField != null ? keywordField : esPath;
    }

    /** 获取 match/match_phrase 查询用的字段路径 */
    public String getMatchField() {
        return esPath;
    }

    @Override
    public String toString() {
        return String.format("SchemaField{%s type=%s nested=%s}", fieldName, type, nestedPath);
    }
}
