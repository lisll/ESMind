package io.esmind.compiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SchemaExplorer — Schema 扫描器，自动发现全部业务表并推断语义分类。
 * <p>
 * 启动时运行一次，扫描 SchemaRegistry 的全部 top-level 表（nested/object），
 * 为每张表生成 TableMetadata：字段统计、日期字段、业务指示字段、建议分类。
 * <p>
 * 替代 YAML 手动配置，让 SchemaRegistry 成为唯一事实来源。
 * 人工可以通过 SemanticCatalog 确认/覆盖自动推荐的结果。
 */
public class SchemaExplorer {

    private static final Logger log = LoggerFactory.getLogger(SchemaExplorer.class);

    private final SchemaRegistry schemaRegistry;
    private List<TableMetadata> tableMetadataList;
    private Map<String, TableMetadata> tableByName;
    private boolean explored = false;

    // ===== 业务指示字段模式 → 分类映射 =====
    // 按优先级降序排列（匹配第一个即确认分类）
    private static final List<CategoryRule> CATEGORY_RULES = List.of(
        new CategoryRule("diagnosis",     "diagnosis",       List.of("diagnosis_name")),
        new CategoryRule("order",         "医嘱",            List.of("order_item_name", "order_class_name")),
        new CategoryRule("lab",           "检验",            List.of("lab_item_name", "lab_sub_item_name", "norm_lab_item_name")),
        new CategoryRule("exam",          "检查",            List.of("norm_exam_item_name", "exam_item_name")),
        new CategoryRule("surgery",       "手术",            List.of("operation_name", "surgery_name")),
        new CategoryRule("prescription",  "处方/药品",       List.of("drug_trade_name", "drug_generic_name", "medicine_code")),
        new CategoryRule("discharge",     "出院",            List.of("discharge_diagnosis", "discharge_time")),
        new CategoryRule("admission",     "入院",            List.of("admission_time", "chief_complaint")),
        new CategoryRule("temperature",   "体温",            List.of("temperature", "vital_sign")),
        new CategoryRule("fee",           "费用",            List.of("charge_fee", "charge_class_name", "charge_item_code")),
        new CategoryRule("anesthesia",    "麻醉",            List.of("anesthesia_method_name", "anesthesia_doctor_code")),
        new CategoryRule("pathology",     "病理",            List.of("pathology", "pathological")),
        new CategoryRule("ultrasound",    "超声",            List.of("ultrasound", "chaosheng")),
        new CategoryRule("death",         "死亡",            List.of("death", "siwang")),
        new CategoryRule("blood",         "输血",            List.of("blood_transfusion", "blood_type", "abo_blood_type")),
        new CategoryRule("consent",       "知情同意书",      List.of("consent", "tongyishu")),
        new CategoryRule("nursing",       "护理",            List.of("nursing_record", "huli", "care_record"))
    );

    public SchemaExplorer(SchemaRegistry schemaRegistry) {
        this.schemaRegistry = schemaRegistry;
    }

    /**
     * 执行扫描，生成全部 top-level 表的 TableMetadata。
     * 启动时调用一次即可，结果可缓存。
     */
    public List<TableMetadata> explore() {
        if (explored && tableMetadataList != null) {
            return tableMetadataList;
        }

        List<TableMetadata> result = new ArrayList<>();
        tableByName = new LinkedHashMap<>();

        Collection<SchemaField> allFields = schemaRegistry.getAllFields();
        if (allFields == null || allFields.isEmpty()) {
            log.warn("SchemaRegistry has no fields, SchemaExplorer returns empty");
            return result;
        }

        // 1. 按表名分组（提取表前缀）
        Map<String, List<SchemaField>> tableGroups = new LinkedHashMap<>();
        for (SchemaField f : allFields) {
            String tbl = extractTableName(f.getFieldName());
            if (tbl == null || tbl.isEmpty()) continue;
            tableGroups.computeIfAbsent(tbl, k -> new ArrayList<>()).add(f);
        }

        // 2. 只处理 top-level 表（自身是 nested/object 或不带 . 的表）
        Set<String> topLevelTables = new LinkedHashSet<>();
        for (SchemaField f : allFields) {
            if (schemaRegistry.isTopLevelTable(f)) {
                topLevelTables.add(f.getFieldName());
            }
        }

        // 3. 为每张 top-level 表生成 metadata
        for (String tableName : topLevelTables) {
            List<SchemaField> tableFields = tableGroups.getOrDefault(tableName, Collections.emptyList());
            // 也收集子字段（prefix match）
            String prefix = tableName + ".";
            List<SchemaField> allTableFields = new ArrayList<>(tableFields);
            for (Map.Entry<String, List<SchemaField>> entry : tableGroups.entrySet()) {
                if (entry.getKey().equals(tableName)) continue;
                if (entry.getKey().startsWith(prefix)) {
                    allTableFields.addAll(entry.getValue());
                }
            }

            TableMetadata meta = buildMetadata(tableName, allTableFields);
            result.add(meta);
            tableByName.put(tableName, meta);
        }

        // 4. 排序：按分类 + 字段数降序
        result.sort((a, b) -> {
            int cat = compareCategory(a.getSuggestedCategory(), b.getSuggestedCategory());
            if (cat != 0) return cat;
            return Integer.compare(b.getFieldCount(), a.getFieldCount());
        });

        tableMetadataList = result;
        explored = true;

        log.info("SchemaExplorer: discovered {} top-level tables with categories", result.size());
        if (log.isDebugEnabled()) {
            for (TableMetadata m : result) {
                log.debug("  {} ({}): {} [{}] — {}",
                        m.getTableName(), m.getFieldCount(), m.getSuggestedCategory(),
                        m.getSuggestionConfidence(), m.getSuggestionReason());
            }
        }

        return result;
    }

    /**
     * 根据关键词在所有业务表中模糊匹配。
     * 匹配策略：关键词拆分 → 按字段模式分类匹配 → 按字段名拼音匹配 → 按表名匹配
     */
    public List<String> findTableByKeyword(String keyword) {
        if (keyword == null || keyword.isEmpty()) return Collections.emptyList();
        if (!explored) explore();

        String kw = keyword.toLowerCase().trim();

        // 1. 精确匹配表名
        if (tableByName.containsKey(kw)) {
            return List.of(kw);
        }

        // 2. 按分类匹配：将关键词拆分为候选分类词
        Set<String> candidates = new LinkedHashSet<>();
        for (TableMetadata meta : tableMetadataList) {
            String cat = meta.getSuggestedCategory();
            if (cat == null) continue;

            // 检查关键词是否包含分类描述词（如"医嘱"→ order）
            for (CategoryRule rule : CATEGORY_RULES) {
                if (!rule.category.equals(cat)) continue;
                for (String cn : rule.chineseNames) {
                    if (kw.contains(cn) || cn.contains(kw)) {
                        candidates.add(meta.getTableName());
                        break;
                    }
                }
            }

            // 检查表名包含关键词（如"menzhen" → 门诊相关表）
            if (meta.getTableName().contains(kw) || kw.contains(meta.getTableName())) {
                candidates.add(meta.getTableName());
            }

            // 检查业务指示字段名包含关键词片段
            if (meta.getBusinessIndicatorFields() != null) {
                for (String ind : meta.getBusinessIndicatorFields()) {
                    if (ind.contains(kw) || kw.contains(ind)) {
                        candidates.add(meta.getTableName());
                        break;
                    }
                }
            }
        }

        return new ArrayList<>(candidates);
    }

    /**
     * 按建议分类查找表。
     */
    public List<TableMetadata> findByCategory(String category) {
        if (!explored) explore();
        return tableMetadataList.stream()
                .filter(m -> category.equals(m.getSuggestedCategory()))
                .collect(Collectors.toList());
    }

    public TableMetadata getByTableName(String name) {
        if (!explored) explore();
        return tableByName != null ? tableByName.get(name) : null;
    }

    public List<TableMetadata> getAllMetadata() {
        if (!explored) explore();
        return tableMetadataList;
    }

    public boolean isExplored() { return explored; }
    public int getTableCount() { return tableMetadataList != null ? tableMetadataList.size() : 0; }

    // ========================================================================
    // 内部方法
    // ========================================================================

    private TableMetadata buildMetadata(String tableName, List<SchemaField> fields) {
        TableMetadata meta = new TableMetadata();
        meta.setTableName(tableName);

        // 确定类型
        String type = null;
        for (SchemaField f : fields) {
            if (f.getFieldName().equals(tableName) && f.getType() != null) {
                type = f.getType();
                break;
            }
        }
        meta.setType(type != null ? type : "unknown");
        meta.setFieldCount(fields.size());

        // 收集日期/关键字/数值字段
        List<String> dateFields = new ArrayList<>();
        List<String> keywordFields = new ArrayList<>();
        List<String> numericFields = new ArrayList<>();
        List<String> indicatorFields = new ArrayList<>();
        Set<String> fieldNames = new HashSet<>();

        for (SchemaField f : fields) {
            String fn = f.getFieldName();
            if (fn == null) continue;
            // 跳过表自身的元字段（只取子字段）
            if (fn.equals(tableName)) continue;
            fieldNames.add(fn);

            if (f.isDateField()) dateFields.add(fn);
            if ("keyword".equals(f.getType())) keywordFields.add(fn);
            if (f.isNumeric()) numericFields.add(fn);
        }

        meta.setDateFields(dateFields.isEmpty() ? null : dateFields);
        meta.setKeywordFields(keywordFields.isEmpty() ? null : keywordFields);
        meta.setNumericFields(numericFields.isEmpty() ? null : numericFields);

        // 推断业务指示字段
        for (String fn : fieldNames) {
            String shortName = fn.contains(".") ? fn.substring(fn.lastIndexOf('.') + 1) : fn;
            for (CategoryRule rule : CATEGORY_RULES) {
                for (String indicator : rule.indicators) {
                    if (shortName.contains(indicator) || indicator.contains(shortName)) {
                        indicatorFields.add(fn);
                    }
                }
            }
        }
        meta.setBusinessIndicatorFields(indicatorFields.isEmpty() ? null : indicatorFields);

        // 推断分类
        inferCategory(meta, fieldNames);

        return meta;
    }

    private void inferCategory(TableMetadata meta, Set<String> fieldNames) {
        List<String> fieldNameList = new ArrayList<>(fieldNames);
        // 也检查短字段名（去掉表前缀）
        List<String> shortNames = fieldNameList.stream()
                .map(fn -> fn.contains(".") ? fn.substring(fn.lastIndexOf('.') + 1) : fn)
                .collect(Collectors.toList());

        for (CategoryRule rule : CATEGORY_RULES) {
            int matchCount = 0;
            for (String indicator : rule.indicators) {
                for (String sn : shortNames) {
                    if (sn.contains(indicator) || indicator.contains(sn)) {
                        matchCount++;
                        break;
                    }
                }
            }
            if (matchCount > 0) {
                String confidence = matchCount >= 2 ? "HIGH" : "MEDIUM";
                meta.setSuggestedCategory(rule.category);
                meta.setSuggestionConfidence(confidence);
                meta.setSuggestionReason("包含 " + matchCount + "/" + rule.indicators.size()
                        + " 个 " + String.join("/", rule.chineseNames) + "类字段"
                        + ("HIGH".equals(confidence) ? "" : "（置信度一般，建议人工确认）"));
                return;
            }
        }

        // 无法推断的兜底
        meta.setSuggestedCategory("unknown");
        meta.setSuggestionConfidence("LOW");
        meta.setSuggestionReason("无匹配的业务指示字段，需要人工分类");
    }

    private int compareCategory(String a, String b) {
        // 已知分类排在 unknown 前面
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        if ("unknown".equals(a) && !"unknown".equals(b)) return 1;
        if (!"unknown".equals(a) && "unknown".equals(b)) return -1;
        return a.compareTo(b);
    }

    private String extractTableName(String fieldPath) {
        if (fieldPath == null) return null;
        int dot = fieldPath.indexOf('.');
        return dot > 0 ? fieldPath.substring(0, dot) : fieldPath;
    }

    /**
     * 分类规则：category=唯一标识, chineseNames=中文名称列表（用于匹配用户查询关键词）
     */
    private static class CategoryRule {
        final String category;
        final List<String> chineseNames;
        final List<String> indicators;  // 字段名模式

        CategoryRule(String category, String chineseName, List<String> indicators) {
            this.category = category;
            this.chineseNames = List.of(chineseName.split("/"));
            this.indicators = indicators;
        }
    }
}
