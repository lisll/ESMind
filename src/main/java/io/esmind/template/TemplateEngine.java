package io.esmind.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.esmind.ast.QueryNode;
import io.esmind.semantic.SemanticIR;
import io.esmind.semantic.SynonymDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * Template Engine — 模板加载 + Entity 解析。
 * <p>
 * 职责：
 * <ol>
 *   <li>从 templates.json 加载查询模板</li>
 *   <li>{@link #resolve(SemanticIR)} 将 LLM/FastPath 输出的部分 Entity 补全为完整 Entity</li>
 *   <li>{@link #buildNode(SemanticIR.Entity)} （待废弃）旧 AST 节点构建入口</li>
 * </ol>
 */
public class TemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(TemplateEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 按 entity type 索引的模板列表 */
    private final Map<String, List<QueryTemplate>> templatesByEntityType = new HashMap<>();
    private final List<QueryTemplate> allTemplates;

    /** 通用类别词 → exists 字段映射 */
    private static final Map<String, String> CATEGORY_WORDS = new HashMap<>();
    static {
        CATEGORY_WORDS.put("检验报告", "jianyanbaogaofu");
        CATEGORY_WORDS.put("化验", "jianyanbaogaofu");
        CATEGORY_WORDS.put("检验", "jianyanbaogaofu");
        CATEGORY_WORDS.put("处方", "menzhenxiyichufang");
        CATEGORY_WORDS.put("药品", "menzhenxiyichufang");
        CATEGORY_WORDS.put("用药", "menzhenxiyichufang");
        CATEGORY_WORDS.put("手术", "shoushujilu");
        CATEGORY_WORDS.put("检查", "jianchabaogaofu");
        CATEGORY_WORDS.put("检查报告", "jianchabaogaofu");
        CATEGORY_WORDS.put("辅助检查", "jianchabaogaofu");
        CATEGORY_WORDS.put("辅助检查结果", "jianchabaogaofu");
        CATEGORY_WORDS.put("检验检查", "jianyanbaogaofu");
        CATEGORY_WORDS.put("病理", "binglizhenduan");
        CATEGORY_WORDS.put("病理报告", "binglizhenduan");
        CATEGORY_WORDS.put("门诊就诊", "menzhenjiuzhenjilu");
        CATEGORY_WORDS.put("门诊就诊记录", "menzhenjiuzhenjilu");
        CATEGORY_WORDS.put("门诊记录", "menzhenjiuzhenjilu");
        CATEGORY_WORDS.put("出院小结", "chuyuanxiaojiexin");
        CATEGORY_WORDS.put("入院记录", "ruyuanjilu");
        CATEGORY_WORDS.put("体温", "hulitizhengyangli");
        CATEGORY_WORDS.put("体温单", "hulitizhengyangli");
        CATEGORY_WORDS.put("体温记录", "hulitizhengyangli");
        CATEGORY_WORDS.put("病案首页", "binganshouye");
        CATEGORY_WORDS.put("首页诊断", "shouyezhenduan");
    }

    /** 各业务表 → 时间字段映射（Resolution 阶段用于时间 Entity 补全） */
    private static final Map<String, String> TABLE_TIME_FIELDS = new HashMap<>();
    static {
        TABLE_TIME_FIELDS.put("menzhenjiuzhenjilu", "visit_time");
        TABLE_TIME_FIELDS.put("zhuyuanfeiyong", "visit_date");
        TABLE_TIME_FIELDS.put("shoumashuzhongyongyao", "pharmacy_start_time");
        TABLE_TIME_FIELDS.put("binganshouye", "admission_time");
        TABLE_TIME_FIELDS.put("hulitizhengyangli", "record_time");
        TABLE_TIME_FIELDS.put("structureddatafu", "element_date_time");
        TABLE_TIME_FIELDS.put("hulipdayizhu", "execute_orders_schedule");
        TABLE_TIME_FIELDS.put("yangbenkufu", "instock_date");
        TABLE_TIME_FIELDS.put("binglizhenduan", "diagnosis_time");
        TABLE_TIME_FIELDS.put("yizhu", "order_time");
        TABLE_TIME_FIELDS.put("menzhenfeiyongmingxi", "charge_time");
        TABLE_TIME_FIELDS.put("jianchabaogaofu", "report_time");
        TABLE_TIME_FIELDS.put("shouyezhenduan", "diagnosis_time");
        TABLE_TIME_FIELDS.put("hulichuruliangjilu", "record_time");
        TABLE_TIME_FIELDS.put("chaoshengexamfu", "report_time");
        TABLE_TIME_FIELDS.put("shouyeshoushu", "operation_date");
        TABLE_TIME_FIELDS.put("menzhenzhenduan", "diagnosis_time");
        TABLE_TIME_FIELDS.put("jianyanbaogaozhubiaofu", "report_time");
        TABLE_TIME_FIELDS.put("BLWS", "create_date_time");
        TABLE_TIME_FIELDS.put("jianyanbaogaomingxifu", "report_time");
        TABLE_TIME_FIELDS.put("shouyeshushi", "operation_date");
        TABLE_TIME_FIELDS.put("menzhenxiyichufang", "order_time");
        TABLE_TIME_FIELDS.put("shoumamazuijilu", "anesthesia_start_time");
        TABLE_TIME_FIELDS.put("jianyanbaogaofu", "report_time");
        TABLE_TIME_FIELDS.put("shoushujilu", "operation_time");
        TABLE_TIME_FIELDS.put("menzhenshuju", "visiting_date");
        TABLE_TIME_FIELDS.put("ruyuanjilu", "admission_time");
        TABLE_TIME_FIELDS.put("chuyuanxiaojiexin", "discharge_time");
    }

    // ========================================================================
    // 构造 & 模板加载
    // ========================================================================

    public TemplateEngine() {
        this.allTemplates = loadTemplates();
        indexTemplates();
        log.info("TemplateEngine loaded {} templates for {} entity types",
                allTemplates.size(), templatesByEntityType.size());
    }

    // ========================================================================
    // Resolution — Entity 补全（核心新增）
    // ========================================================================

    /**
     * 补全 SemanticIR 中所有 Entity 的编译字段。
     * <p>
     * 输入：LLM 或 FastPath 输出的部分 Entity（只有 type/value）
     * 输出：完整的 Entity（clauseType/table/field 等编译字段全部填充）
     * <p>
     * v2.1 改进：
     * - disease 类型展开为住院诊断 + 门诊诊断两个 SHOULD 组合
     * - 时间过滤只应用到主动查询表（report_type/lab_item/medicine/surgery），不应用到诊断表
     */
    public SemanticIR resolve(SemanticIR ir) {
        List<SemanticIR.Entity> resolved = new ArrayList<>();
        List<SemanticIR.Entity> timeValues = new ArrayList<>();
        // 记录主动查询表（report_type/lab_item/medicine/surgery → 时间应作用的目标）
        Set<String> activeTables = new LinkedHashSet<>();

        // Pass 1: 解析非时间 Entity
        for (SemanticIR.Entity e : ir.getEntities()) {
            if ("time".equals(e.getType())) {
                timeValues.add(e);
            } else if ("disease".equals(e.getType())) {
                // 诊断 → 展开为住院+门诊两个实体，用 group 标记 SHOULD 组合
                // 诊断表不加入 activeTables（时间不应用到诊断表）
                List<SemanticIR.Entity> diseaseEntities = expandDisease(e);
                for (SemanticIR.Entity de : diseaseEntities) {
                    resolved.add(de);
                }
            } else {
                SemanticIR.Entity resolvedEntity = resolveEntity(e);
                resolved.add(resolvedEntity);
                // 记录主动查询表（report_type/lab_item/medicine/surgery → 时间应作用的目标）
                // 注意：不加入诊断表，避免时间错误应用到诊断上
                if (resolvedEntity.getTable() != null
                        && !"shouyezhenduan".equals(resolvedEntity.getTable())
                        && !"menzhenshuju".equals(resolvedEntity.getTable())
                        && !"menzhenzhenduan".equals(resolvedEntity.getTable())) {
                    activeTables.add(resolvedEntity.getTable());
                }
            }
        }

        // Pass 2: 时间约束 → 只应用到主动查询表，不应用到诊断表
        if (!timeValues.isEmpty()) {
            if (!activeTables.isEmpty()) {
                for (String table : activeTables) {
                    if (TABLE_TIME_FIELDS.containsKey(table)) {
                        String timeField = TABLE_TIME_FIELDS.get(table);
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
                }
            } else {
                // 只有诊断查询 → 时间作用到诊断表的 diagnosis_time
                String[] diagTables = {"shouyezhenduan", "menzhenzhenduan"};
                for (String dt : diagTables) {
                    if (TABLE_TIME_FIELDS.containsKey(dt)) {
                        for (SemanticIR.Entity tv : timeValues) {
                            SemanticIR.Entity te = new SemanticIR.Entity("time", tv.getValue());
                            te.setUnit(tv.getUnit());
                            te.setTimeType(tv.getTimeType());
                            te.setClauseType("range");
                            te.setTable(dt);
                            te.setField(dt + "." + TABLE_TIME_FIELDS.get(dt));
                            setContext(te, dt);
                            resolved.add(te);
                        }
                    }
                }
            }
        }

        ir.setEntities(resolved);

        // Aggregation 字段自动推导（LLM 不需要输出 field）
        SemanticIR.Aggregation agg = ir.getAggregation();
        if (agg != null && (agg.getField() == null || agg.getField().isEmpty())
                && !"count".equals(agg.getType())) {
            // 从 resolved entities 中找到第一个非时间、非诊断的实体，取其表名
            String aggTable = null;
            for (SemanticIR.Entity e : resolved) {
                String table = e.getTable();
                if (table != null && !"time".equals(e.getType())
                        && !"shouyezhenduan".equals(table)
                        && !"menzhenshuju".equals(table)
                        && !"menzhenzhenduan".equals(table)) {
                    aggTable = table;
                    break;
                }
            }
            if (aggTable != null && TABLE_TIME_FIELDS.containsKey(aggTable)) {
                String dateField = TABLE_TIME_FIELDS.get(aggTable);
                agg.setField(aggTable + "." + dateField);
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

    /** 设置 entity 的 context 和 contextPath（基于表名是否在 nested 列表中） */
    private void setContext(SemanticIR.Entity entity, String table) {
        if (table != null && NESTED_TABLES.contains(table)) {
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

        // 1. 类别词 → exists（lab_item/medicine/surgery 的值命中 CATEGORY_WORDS）
        if (value != null && !value.isEmpty()
                && ("lab_item".equals(type) || "medicine".equals(type) || "surgery".equals(type))) {
            String existsField = CATEGORY_WORDS.get(value);
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
            String table = resolveReportTypeTable(value);
            if (table != null) {
                entity.setClauseType("exists");
                entity.setTable(table);
                entity.setField(table);
                setContext(entity, table);
                return entity;
            }
        }

        // 3. 模板查找
        List<QueryTemplate> candidates = templatesByEntityType.getOrDefault(type, Collections.emptyList());
        if (!candidates.isEmpty()) {
            QueryTemplate tpl = candidates.get(0);
            QueryTemplate.Strategy s = tpl.getStrategy();
            // 根据策略类型设置 clauseType 和 context
            String strategyType = s.getType();
            if (strategyType.startsWith("nested_")) {
                entity.setContext("NESTED");
                entity.setContextPath(s.getTable());
                entity.setClauseType(strategyType.substring(7)); // "nested_term" → "term"
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

        // 4. 兜底：全文搜索
        entity.setClauseType("match_phrase");
        entity.setField("total_src");
        log.warn("No template for type '{}', fallback to total_src match_phrase", type);
        return entity;
    }

    // ========================================================================
    // 旧 buildNode 方法（兼容阶段，后续删除）
    // ========================================================================

    /** @deprecated 使用 {@link #resolveEntity(SemanticIR.Entity)} 替代 */
    @Deprecated
    public QueryNode buildNode(SemanticIR.Entity entity) {
        String type = entity.getType();
        String value = entity.getValue();

        if (value != null && !value.isEmpty() && ("lab_item".equals(type) || "medicine".equals(type) || "surgery".equals(type))) {
            String existsField = CATEGORY_WORDS.get(value);
            if (existsField != null) {
                return buildExistsNodeForField(existsField);
            }
        }

        if ("report_type".equals(type) && value != null) {
            return buildReportTypeNode(value);
        }

        List<QueryTemplate> candidates = templatesByEntityType.getOrDefault(type, Collections.emptyList());
        if (candidates.isEmpty()) {
            return buildFallback(entity);
        }

        QueryTemplate template = candidates.get(0);
        return applyTemplate(template, entity);
    }

    // ========================================================================
    // 内部工具方法
    // ========================================================================

    /** report_type 值 → 表名 */
    private String resolveReportTypeTable(String value) {
        switch (value) {
            case "lab":
            case "检验":
            case "检验报告":
            case "化验":
                return "jianyanbaogaofu";
            case "exam":
            case "检查":
            case "检查报告":
            case "辅助检查":
            case "辅助检查结果":
                return "jianchabaogaofu";
            case "检验检查":
                return "jianyanbaogaofu";
            case "surgery":
            case "手术":
                return "shoushujilu";
            case "pathology":
            case "病理":
            case "病理报告":
                return "binglizhenduan";
            case "outpatient":
            case "门诊":
            case "门诊就诊":
            case "门诊就诊记录":
                return "menzhenjiuzhenjilu";
            case "prescription":
            case "处方":
            case "处方记录":
            case "药品":
            case "用药":
                return "menzhenxiyichufang";
            case "discharge":
            case "出院小结":
                return "chuyuanxiaojiexin";
            case "admission":
            case "入院记录":
                return "ruyuanjilu";
            case "temperature":
            case "体温":
            case "体温单":
            case "体温记录":
                return "hulitizhengyangli";
            case "frontpage":
            case "病案首页":
                return "binganshouye";
            case "frontpage_diag":
            case "首页诊断":
                return "shouyezhenduan";
            default:
                String table = CATEGORY_WORDS.get(value);
                if (table != null) return table;
                log.warn("Unknown report_type value '{}'", value);
                return null;
        }
    }

    // ========================================================================
    // 以下为旧 buildNode 所需方法（兼容阶段，后续随 buildNode 一起删除）
    // ========================================================================

    private static final Set<String> NESTED_TABLES = new HashSet<>(Arrays.asList(
        "jianyanbaogaofu", "shoushujilu", "menzhenxiyichufang",
        "shouyezhenduan", "menzhenshuju", "structureddatafu",
        "binglizhenduan", "jianchabaogaofu", "menzhenzhenduan",
        "chuyuanxiaojiexin", "hulitizhengyangli"
    ));

    private QueryNode applyTemplate(QueryTemplate template, SemanticIR.Entity entity) {
        String strategyType = template.getStrategy().getType();
        switch (strategyType) {
            case "term":          return buildTermNode(template, entity);
            case "match_phrase":  return buildMatchPhraseNode(template, entity);
            case "nested_term":   return buildNestedTermNode(template, entity);
            case "nested_match":  return buildNestedMatchNode(template, entity);
            case "range":         return buildRangeNode(template, entity);
            case "exists":        return buildExistsNode(template, entity);
            default:
                return buildFallback(entity);
        }
    }

    private QueryNode buildTermNode(QueryTemplate template, SemanticIR.Entity entity) {
        String field = template.getStrategy().getKeyword();
        if (field == null) field = template.getStrategy().getField();
        return new QueryNode.TermNode(field, entity.getValue());
    }

    private QueryNode buildMatchPhraseNode(QueryTemplate template, SemanticIR.Entity entity) {
        return new QueryNode.MatchPhraseNode(template.getStrategy().getField(), entity.getValue());
    }

    private QueryNode buildNestedTermNode(QueryTemplate template, SemanticIR.Entity entity) {
        String keyword = template.getStrategy().getKeyword();
        String targetField = keyword != null ? keyword : template.getStrategy().getField();
        QueryNode.BoolNode boolQuery = new QueryNode.BoolNode();
        boolQuery.addMust(new QueryNode.TermNode(targetField, entity.getValue()));

        if (entity.getOperator() != null && entity.getNumericValue() != null
                && template.getStrategy().getValueField() != null) {
            QueryNode.RangeNode range = new QueryNode.RangeNode();
            range.setField(template.getStrategy().getValueField());
            switch (entity.getOperator()) {
                case "gt":  range.setGt(entity.getNumericValue()); break;
                case "gte": range.setGte(entity.getNumericValue()); break;
                case "lt":  range.setLt(entity.getNumericValue()); break;
                case "lte": range.setLte(entity.getNumericValue()); break;
                case "eq":  range.setGte(entity.getNumericValue()); range.setLte(entity.getNumericValue()); break;
            }
            boolQuery.addMust(range);
        }

        QueryNode.NestedNode nested = new QueryNode.NestedNode();
        nested.setPath(template.getStrategy().getTable());
        nested.setQuery(boolQuery);
        nested.setInnerHitsSize(0);
        return nested;
    }

    private QueryNode buildNestedMatchNode(QueryTemplate template, SemanticIR.Entity entity) {
        String value = entity.getValue();
        QueryNode.BoolNode should = new QueryNode.BoolNode();
        should.addShould(new QueryNode.MatchPhraseNode(template.getStrategy().getField(), value));

        if (template.getStrategy().getKeyword() != null) {
            should.addShould(new QueryNode.TermNode(template.getStrategy().getKeyword(), value));
        }

        if (template.getStrategy().isUseSynonyms()) {
            for (String syn : SynonymDictionary.getDefault().getSynonyms(entity.getType(), value)) {
                if (!syn.equals(value)) {
                    should.addShould(new QueryNode.MatchPhraseNode(template.getStrategy().getField(), syn));
                }
            }
        }

        should.setMinimumShouldMatch(1);
        QueryNode.NestedNode nested = new QueryNode.NestedNode();
        nested.setPath(template.getStrategy().getTable());
        nested.setQuery(should);
        nested.setInnerHitsSize(0);
        return nested;
    }

    private QueryNode buildRangeNode(QueryTemplate template, SemanticIR.Entity entity) { return null; }
    private QueryNode buildFallback(SemanticIR.Entity entity) {
        return new QueryNode.MatchPhraseNode("total_src", entity.getValue());
    }
    private QueryNode buildExistsNode(QueryTemplate template, SemanticIR.Entity entity) {
        return buildExistsNodeForField(template.getStrategy().getField());
    }

    private QueryNode buildExistsNodeForField(String field) {
        boolean isNested = field.contains(".") || NESTED_TABLES.contains(field);
        QueryNode.ExistsNode exists = new QueryNode.ExistsNode();
        exists.setField(field);

        if (isNested) {
            String path = extractTable(field);
            QueryNode.NestedNode nested = new QueryNode.NestedNode();
            nested.setPath(path != null ? path : field);
            nested.setQuery(exists);
            nested.setInnerHitsSize(0);
            return nested;
        }
        return exists;
    }

    private QueryNode buildReportTypeNode(String value) {
        String table = resolveReportTypeTable(value);
        if (table == null) {
            return new QueryNode.MatchPhraseNode("total_src", value);
        }
        return buildExistsNodeForField(table);
    }

    private String extractTable(String field) {
        if (field == null) return null;
        int dot = field.indexOf('.');
        return dot > 0 ? field.substring(0, dot) : null;
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

    public List<QueryTemplate> getAllTemplates() { return allTemplates; }

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
