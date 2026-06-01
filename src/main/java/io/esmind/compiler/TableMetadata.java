package io.esmind.compiler;

import java.util.List;

/**
 * TableMetadata — 表元数据，由 SchemaExplorer 自动扫描生成。
 * <p>
 * 描述一张 ES 业务表的结构特征和自动推荐的业务分类。
 * 不依赖人工配置，完全基于 Schema 运行时推断。
 */
public class TableMetadata {

    private String tableName;          // ES 表名（如 shouyezhenduan）
    private String type;               // nested / object
    private int fieldCount;            // 字段数

    private List<String> dateFields;   // date 类型字段
    private List<String> keywordFields; // keyword 类型字段
    private List<String> numericFields; // 数值类型字段

    /** 业务指示性字段（能推断业务含义的关键字段，如 diagnosis_name、order_item_name） */
    private List<String> businessIndicatorFields;

    /** 自动推荐的业务分类（如 diagnosis、lab、order、surgery、exam、admission、discharge） */
    private String suggestedCategory;

    /** 推荐置信度：HIGH / MEDIUM / LOW */
    private String suggestionConfidence;

    /** 自动推荐的解释（如"包含 diagnosis_name 字段"） */
    private String suggestionReason;

    // ===== 构造 & getter/setter =====

    public TableMetadata() {}

    public String getTableName() { return tableName; }
    public void setTableName(String v) { this.tableName = v; }

    public String getType() { return type; }
    public void setType(String v) { this.type = v; }

    public int getFieldCount() { return fieldCount; }
    public void setFieldCount(int v) { this.fieldCount = v; }

    public List<String> getDateFields() { return dateFields; }
    public void setDateFields(List<String> v) { this.dateFields = v; }

    public List<String> getKeywordFields() { return keywordFields; }
    public void setKeywordFields(List<String> v) { this.keywordFields = v; }

    public List<String> getNumericFields() { return numericFields; }
    public void setNumericFields(List<String> v) { this.numericFields = v; }

    public List<String> getBusinessIndicatorFields() { return businessIndicatorFields; }
    public void setBusinessIndicatorFields(List<String> v) { this.businessIndicatorFields = v; }

    public String getSuggestedCategory() { return suggestedCategory; }
    public void setSuggestedCategory(String v) { this.suggestedCategory = v; }

    public String getSuggestionConfidence() { return suggestionConfidence; }
    public void setSuggestionConfidence(String v) { this.suggestionConfidence = v; }

    public String getSuggestionReason() { return suggestionReason; }
    public void setSuggestionReason(String v) { this.suggestionReason = v; }
}
