package io.esmind.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.esmind.compiler.BusinessSemanticRegistry;
import io.esmind.compiler.SchemaExplorer;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.semantic.SemanticIR;
import io.esmind.semantic.SynonymDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * Template Engine — Entity 解析 + 模板映射。
 * <p>
 * 职责：
 * <ol>
 *   <li>从 templates.json 加载实体类型→字段映射模板</li>
 *   <li>{@link #resolve(SemanticIR)} 将 LLM 输出的部分 Entity 补全为完整 Entity（表名、字段、clauseType）</li>
 *   <li>时间约束自动分配到正确的业务表</li>
 * </ol>
 */
public class TemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(TemplateEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 按 entity type 索引的模板列表 */
    private final Map<String, List<QueryTemplate>> templatesByEntityType = new HashMap<>();
    private final List<QueryTemplate> allTemplates;

    /** Runtime Schema Registry */
    private final SchemaRegistry schemaRegistry;

    /** Runtime SchemaExplorer — 自动表发现 + 关键词匹配 fallback */
    private final SchemaExplorer schemaExplorer;

    /** Runtime Business Semantic Registry */
    private final BusinessSemanticRegistry businessRegistry;

    // ========================================================================
    // 运行时查询（替代 CATEGORY_WORDS / TABLE_TIME_FIELDS / resolveReportTypeTable）
    // ========================================================================

    /**
     * 通用表名解析：中文别名 → 业务表名。
     * 查找链：BusinessSemanticRegistry → SchemaRegistry → null
     */
    private String resolveTableName(String alias) {
        if (alias == null || alias.isEmpty()) return null;
        if (businessRegistry != null) {
            String tbl = businessRegistry.findTableByAlias(alias);
            if (tbl != null) return tbl;
        }
        if (schemaRegistry != null) {
            String tbl = schemaRegistry.findTableByAlias(alias);
            if (tbl != null) return tbl;
        }
        // 3. SchemaExplorer 关键词匹配 fallback（不依赖 YAML，纯字段名模式匹配）
        if (schemaExplorer != null) {
            List<String> matches = schemaExplorer.findTableByKeyword(alias);
            if (!matches.isEmpty()) {
                String best = matches.get(0);
                log.info("SchemaExplorer fallback: '{}' → {} (matches: {})", alias, best, matches);
                return best;
            }
        }
        return null;
    }

    /**
     * 获取业务表的时间字段名（不含表前缀）。
     * 查找链：BusinessSemanticRegistry.dateFields → SchemaRegistry.getDateFieldsForTable → null
     */
    private String getTimeFieldForTable(String table) {
        if (table == null) return null;
        // 1. BusinessSemanticRegistry 的 dateFields 配置
        if (businessRegistry != null) {
            List<String> fields = businessRegistry.getDateFields(table);
            if (fields != null && !fields.isEmpty()) {
                return fields.get(0);
            }
        }
        // 2. SchemaRegistry 的 date 字段探测
        if (schemaRegistry != null) {
            List<String> fields = schemaRegistry.getDateFieldsForTable(table);
            if (fields != null && !fields.isEmpty()) {
                // 返回去掉表前缀的部分
                String fullField = fields.get(0);
                String prefix = table + ".";
                return fullField.startsWith(prefix) ? fullField.substring(prefix.length()) : fullField;
            }
        }
        return null;
    }

    /** 判断表是否为 nested 类型 */
    private boolean isNestedTable(String table) {
        if (table == null) return false;
        if (schemaRegistry != null) {
            return schemaRegistry.getNestedPaths().contains(table);
        }
        return false;
    }

    // ========================================================================
    // 构造 & 模板加载
    // ========================================================================

    public TemplateEngine(SchemaRegistry schemaRegistry, BusinessSemanticRegistry businessRegistry,
                          SchemaExplorer schemaExplorer) {
        this.schemaRegistry = schemaRegistry;
        this.businessRegistry = businessRegistry;
        this.schemaExplorer = schemaExplorer;
        this.allTemplates = loadTemplates();
        indexTemplates();
        log.info("TemplateEngine loaded {} templates for {} entity types, schema={}, business={}, explorer={}",
                allTemplates.size(), templatesByEntityType.size(),
                schemaRegistry != null, businessRegistry != null, schemaExplorer != null);
    }

    // ========================================================================
    // Resolution — Entity 补全（核心）
    // ========================================================================

    /**
     * 补全 SemanticIR 中所有 Entity 的编译字段。
     * <p>
     * 输入：LLM 输出的部分 Entity（只有 type/value）
     * 输出：完整的 Entity（clauseType/table/field 等编译字段全部填充）
     */
    public SemanticIR resolve(SemanticIR ir) {
        List<SemanticIR.Entity> resolved = new ArrayList<>();
        List<SemanticIR.Entity> timeValues = new ArrayList<>();
        // 记录主动查询表（时间应作用的目标）
        Set<String> activeTables = new LinkedHashSet<>();

        // Pass 1: 解析非时间 Entity
        for (SemanticIR.Entity e : ir.getEntities()) {
            if ("time".equals(e.getType())) {
                timeValues.add(e);
            } else if ("disease".equals(e.getType())) {
                // 诊断 → 展开为住院+门诊两个实体，用 group 标记 SHOULD 组合
                List<SemanticIR.Entity> diseaseEntities = expandDisease(e);
                for (SemanticIR.Entity de : diseaseEntities) {
                    resolved.add(de);
                }
            } else {
                SemanticIR.Entity resolvedEntity = resolveEntity(e);
                resolved.add(resolvedEntity);
                // 记录主动查询表（不加入诊断表，避免时间错误应用到诊断上）
                if (resolvedEntity.getTable() != null
                        && !"shouyezhenduan".equals(resolvedEntity.getTable())
                        && !"menzhenshuju".equals(resolvedEntity.getTable())
                        && !"menzhenzhenduan".equals(resolvedEntity.getTable())) {
                    activeTables.add(resolvedEntity.getTable());
                }
            }
        }

        // Pass 2: 时间约束 → 只应用到主动查询表
        if (!timeValues.isEmpty()) {
            if (!activeTables.isEmpty()) {
                for (String table : activeTables) {
                    String timeField = getTimeFieldForTable(table);
                    if (timeField != null) {
                        for (SemanticIR.Entity tv : timeValues) {
                            SemanticIR.Entity te = new SemanticIR.Entity("time", tv.getValue());
                            te.setUnit(tv.getUnit());
                            te.setTimeType(tv.getTimeType());
                            te.setClauseType("range");
                            te.setTable(table);
                            te.setField(table + "." + timeField);
                            setContext(te, table);
                            resolved.add(te);
                        }
                    }
                }
            } else {
                // 只有诊断查询 → 时间作用到诊断表的 diagnosis_time
                String[] diagTables = {"shouyezhenduan", "menzhenzhenduan"};
                for (String dt : diagTables) {
                    String timeField = getTimeFieldForTable(dt);
                    if (timeField != null) {
                        for (SemanticIR.Entity tv : timeValues) {
                            SemanticIR.Entity te = new SemanticIR.Entity("time", tv.getValue());
                            te.setUnit(tv.getUnit());
                            te.setTimeType(tv.getTimeType());
                            te.setClauseType("range");
                            te.setTable(dt);
                            te.setField(dt + "." + timeField);
                            setContext(te, dt);
                            resolved.add(te);
                        }
                    }
                }
            }
        }

        ir.setEntities(resolved);

        // Aggregation 字段自动推导
        SemanticIR.Aggregation agg = ir.getAggregation();
        if (agg != null && (agg.getField() == null || agg.getField().isEmpty())
                && !"count".equals(agg.getType())) {
            String aggTable = null;
            for (SemanticIR.Entity e : resolved) {
                String table = e.getTable();
                if (table != null && !"time".equals(e.getType())) {
                    aggTable = table;
                    break;
                }
            }
            if (aggTable != null && getTimeFieldForTable(aggTable) != null) {
                String dateField = getTimeFieldForTable(aggTable);
                agg.setField(aggTable + "." + dateField);
                if (schemaRegistry != null && schemaRegistry.getNestedPaths().contains(aggTable)) {
                    agg.setNestedPath(aggTable);
                }
                log.info("Auto-resolved aggregation field: {} (table={})", agg.getField(), aggTable);
            } else if (aggTable != null) {
                log.warn("No date field mapping for aggregation table: {}", aggTable);
            }
        }

        return ir;
    }

    /** 将 disease 展开为住院诊断 + 门诊诊断两个 SHOULD 组合 */
    private List<SemanticIR.Entity> expandDisease(SemanticIR.Entity original) {
        List<SemanticIR.Entity> results = new ArrayList<>();

        // 住院诊断 (shouyezhenduan)
        SemanticIR.Entity inpat = copyWithGroup(original, "disease_" + original.getValue());
        inpat.setClauseType("match_phrase");
        inpat.setTable("shouyezhenduan");
        inpat.setField("shouyezhenduan.diagnosis_name");
        inpat.setKeyword("shouyezhenduan.diagnosis_name.accurate");
        inpat.setUseSynonyms(true);
        setContext(inpat, "shouyezhenduan");

        // 门诊诊断 (menzhenshuju.diagnosis_name)
        SemanticIR.Entity outpat = copyWithGroup(original, "disease_" + original.getValue());
        outpat.setClauseType("match_phrase");
        outpat.setTable("menzhenshuju");
        outpat.setField("menzhenshuju.diagnosis_name.diagnosis_name");
        outpat.setUseSynonyms(true);
        setContext(outpat, "menzhenshuju");

        // 门诊诊断表 (menzhenzhenduan)
        SemanticIR.Entity mzDiag = copyWithGroup(original, "disease_" + original.getValue());
        mzDiag.setClauseType("match_phrase");
        mzDiag.setTable("menzhenzhenduan");
        mzDiag.setField("menzhenzhenduan.diagnosis_name");
        mzDiag.setUseSynonyms(true);
        setContext(mzDiag, "menzhenzhenduan");

        results.add(inpat);
        results.add(outpat);
        results.add(mzDiag);
        return results;
    }

    private SemanticIR.Entity copyWithGroup(SemanticIR.Entity src, String group) {
        SemanticIR.Entity e = new SemanticIR.Entity(src.getType(), src.getValue());
        e.setGroup(group);
        return e;
    }

    /** 设置 entity 的 context 和 contextPath */
    private void setContext(SemanticIR.Entity entity, String table) {
        if (table == null) return;
        boolean isNested = isNestedTable(table);
        if (isNested) {
            entity.setContext("NESTED");
            entity.setContextPath(table);
        } else {
            entity.setContext("ROOT");
        }
    }

    /**
     * 补全单个 Entity 的编译字段。
     */
    public SemanticIR.Entity resolveEntity(SemanticIR.Entity entity) {
        String type = entity.getType();
        String value = entity.getValue();

        // 1. 类别词 → exists
        if (value != null && !value.isEmpty()
                && ("lab_item".equals(type) || "medicine".equals(type) || "surgery".equals(type))) {
            String existsField = resolveTableName(value);
            if (existsField != null) {
                entity.setClauseType("exists");
                entity.setTable(existsField);
                entity.setField(existsField);
                setContext(entity, existsField);
                return entity;
            }
        }

        // 2. report_type → value 路由
        if ("report_type".equals(type) && value != null) {
            String table = resolveTableName(value);
            if (table != null) {
                entity.setClauseType("exists");
                entity.setTable(table);
                String dateField = getTimeFieldForTable(table);
                if (dateField != null && !isNestedTable(table)) {
                    entity.setField(table + "." + dateField);
                } else {
                    entity.setField(table);
                }
                setContext(entity, table);
                return entity;
            }
        }

        // 3. 模板查找
        List<QueryTemplate> candidates = templatesByEntityType.getOrDefault(type, Collections.emptyList());
        if (!candidates.isEmpty()) {
            QueryTemplate tpl = candidates.get(0);
            QueryTemplate.Strategy s = tpl.getStrategy();
            String strategyType = s.getType();
            if (strategyType.startsWith("nested_")) {
                entity.setContext("NESTED");
                entity.setContextPath(s.getTable());
                entity.setClauseType(strategyType.substring(7));
            } else {
                entity.setContext("ROOT");
                entity.setClauseType(strategyType);
            }
            entity.setTable(s.getTable());
            entity.setField(s.getField());
            entity.setKeyword(s.getKeyword());
            entity.setValueField(s.getValueField());
            entity.setUseSynonyms(s.isUseSynonyms());
            return entity;
        }

        // 4. 兜底：优先选择填充率高的字段，实在不行才用 total_src
        String bestField = "total_src";
        if (schemaRegistry != null) {
            Double bestRate = null;
            // 遍历所有字段，找到填充率最高的、可搜索的字段
            for (io.esmind.compiler.SchemaField f : schemaRegistry.getAllFields()) {
                // 跳过 nested/object 类型的字段本身，只看具体数据字段
                if ("nested".equals(f.getType()) || "object".equals(f.getType())) continue;
                // 优先选择有 keyword 子字段的，或者类型是 keyword/text/date/numeric 的
                boolean isSearchable = f.getKeywordField() != null
                        || "keyword".equals(f.getType())
                        || "text".equals(f.getType())
                        || "date".equals(f.getType())
                        || f.isNumeric();
                if (isSearchable && f.getFillRate() != null) {
                    if (bestRate == null || f.getFillRate() > bestRate) {
                        bestRate = f.getFillRate();
                        // 优先用 keyword 字段做 match_phrase，或者原字段
                        bestField = f.getKeywordField() != null ? f.getKeywordField() : f.getFieldName();
                    }
                }
            }
            log.info("Fallback field selection: {} (fill rate: {})", bestField, bestRate);
        }
        entity.setClauseType("match_phrase");
        entity.setField(bestField);
        log.warn("No template for type '{}', fallback to {} match_phrase", type, bestField);
        return entity;
    }

    // ========================================================================
    // 模板加载
    // ========================================================================

    private List<QueryTemplate> loadTemplates() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("templates.json")) {
            if (is == null) {
                log.warn("templates.json not found on classpath, using built-in defaults");
                return getDefaultTemplates();
            }
            TemplateFile data = MAPPER.readValue(is, TemplateFile.class);
            return data.getTemplates();
        } catch (Exception e) {
            log.error("Failed to load templates.json", e);
            return getDefaultTemplates();
        }
    }

    private void indexTemplates() {
        for (QueryTemplate tpl : allTemplates) {
            for (String et : tpl.getEntityTypes()) {
                templatesByEntityType.computeIfAbsent(et, k -> new ArrayList<>()).add(tpl);
            }
        }
    }

    private List<QueryTemplate> getDefaultTemplates() {
        QueryTemplate diag = new QueryTemplate();
        diag.setName("diagnosis_match");
        diag.setEntityTypes(Arrays.asList("disease"));
        QueryTemplate.Strategy strat = new QueryTemplate.Strategy();
        strat.setType("nested_match");
        strat.setTable("shouyezhenduan");
        strat.setField("shouyezhenduan.diagnosis_name");
        strat.setKeyword("shouyezhenduan.diagnosis_name.accurate");
        strat.setUseSynonyms(true);
        diag.setStrategy(strat);

        QueryTemplate lab = new QueryTemplate();
        lab.setName("lab_test_by_name");
        lab.setEntityTypes(Arrays.asList("lab_item"));
        QueryTemplate.Strategy labStrat = new QueryTemplate.Strategy();
        labStrat.setType("nested_term");
        labStrat.setTable("jianyanbaogaofu");
        labStrat.setField("jianyanbaogaofu.lab_sub_item_name");
        labStrat.setKeyword("jianyanbaogaofu.lab_sub_item_name.accurate");
        labStrat.setValueField("jianyanbaogaofu.lab_sub_item_result");
        lab.setStrategy(labStrat);

        return Arrays.asList(diag, lab);
    }

    // ========================================================================
    // POJO
    // ========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TemplateFile {
        private String version; private List<QueryTemplate> templates;
        public String getVersion() { return version; }
        public void setVersion(String v) { this.version = v; }
        public List<QueryTemplate> getTemplates() { return templates; }
        public void setTemplates(List<QueryTemplate> t) { this.templates = t; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryTemplate {
        private String name, description;
        private List<String> entityTypes;
        private List<Parameter> parameters;
        private Strategy strategy;

        public String getName() { return name; }
        public void setName(String n) { this.name = n; }
        public String getDescription() { return description; }
        public void setDescription(String d) { this.description = d; }
        public List<String> getEntityTypes() { return entityTypes; }
        public void setEntityTypes(List<String> et) { this.entityTypes = et; }
        public List<Parameter> getParameters() { return parameters; }
        public void setParameters(List<Parameter> p) { this.parameters = p; }
        public Strategy getStrategy() { return strategy; }
        public void setStrategy(Strategy s) { this.strategy = s; }

        public static class Parameter {
            private String name, type, description;
            private boolean optional;
            public String getName() { return name; }
            public void setName(String n) { this.name = n; }
            public String getType() { return type; }
            public void setType(String t) { this.type = t; }
            public String getDescription() { return description; }
            public void setDescription(String d) { this.description = d; }
            public boolean isOptional() { return optional; }
            public void setOptional(boolean o) { this.optional = o; }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Strategy {
            private String type, table, field, keyword, valueField, dateFormat;
            private boolean useSynonyms;

            public String getType() { return type; }
            public void setType(String t) { this.type = t; }
            public String getTable() { return table; }
            public void setTable(String t) { this.table = t; }
            public String getField() { return field; }
            public void setField(String f) { this.field = f; }
            public String getKeyword() { return keyword; }
            public void setKeyword(String k) { this.keyword = k; }
            public String getValueField() { return valueField; }
            public void setValueField(String v) { this.valueField = v; }
            public boolean isUseSynonyms() { return useSynonyms; }
            public void setUseSynonyms(boolean u) { this.useSynonyms = u; }
            public String getDateFormat() { return dateFormat; }
            public void setDateFormat(String d) { this.dateFormat = d; }
        }
    }
}
