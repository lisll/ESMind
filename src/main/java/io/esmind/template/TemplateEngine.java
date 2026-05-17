package io.esmind.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.esmind.ast.QueryNode;
import io.esmind.semantic.SemanticIR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Template Engine — 从 JSON 模板文件加载查询策略。
 *
 * <p>取代 StrategySelector 中的硬编码策略。
 * 每个 entity type 对应一个或多个模板，模板定义了：
 * <ul>
 *   <li>对应的 ES 查询类型（term/match_phrase/nested_term/nested_match/range）</li>
 *   <li>目标表和字段路径</li>
 *   <li>参数注入规则</li>
 * </ul>
 *
 * <p>设计来源：OpenSearch ML Commons 的两步模板选择 + 参数填充模式。
 */
public class TemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(TemplateEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 按 entity type 索引的模板列表 */
    private final Map<String, List<QueryTemplate>> templatesByEntityType = new HashMap<>();

    /** 所有模板 */
    private final List<QueryTemplate> allTemplates;

    public TemplateEngine() {
        this.allTemplates = loadTemplates();
        indexTemplates();
        log.info("TemplateEngine loaded {} templates for {} entity types",
                allTemplates.size(), templatesByEntityType.size());
    }

    /**
     * 根据 entity type 选择最优模板并构建 AST 节点。
     */
    public QueryNode buildNode(SemanticIR.Entity entity) {
        String type = entity.getType();
        List<QueryTemplate> candidates = templatesByEntityType.getOrDefault(type, Collections.emptyList());

        if (candidates.isEmpty()) {
            log.warn("No template found for entity type: {}, using fallback", type);
            return buildFallback(entity);
        }

        // 取第一个匹配的模板（后续可扩展为评分选择）
        QueryTemplate template = candidates.get(0);
        return applyTemplate(template, entity);
    }

    /**
     * 按模板构建 AST 节点。
     */
    private QueryNode applyTemplate(QueryTemplate template, SemanticIR.Entity entity) {
        String strategyType = template.getStrategy().getType();

        switch (strategyType) {
            case "term":
                return buildTermNode(template, entity);
            case "match_phrase":
                return buildMatchPhraseNode(template, entity);
            case "nested_term":
                return buildNestedTermNode(template, entity);
            case "nested_match":
                return buildNestedMatchNode(template, entity);
            case "range":
                return buildRangeNode(template, entity);
            default:
                log.warn("Unknown strategy type: {}, using fallback", strategyType);
                return buildFallback(entity);
        }
    }

    /** term 查询：精确匹配 keyword 字段 */
    private QueryNode buildTermNode(QueryTemplate template, SemanticIR.Entity entity) {
        String field = template.getStrategy().getKeyword();
        if (field == null) {
            field = template.getStrategy().getField();
        }
        return new QueryNode.TermNode(field, entity.getValue());
    }

    /** match_phrase 查询：全文搜索 */
    private QueryNode buildMatchPhraseNode(QueryTemplate template, SemanticIR.Entity entity) {
        return new QueryNode.MatchPhraseNode(template.getStrategy().getField(), entity.getValue());
    }

    /** nested + term：嵌套表精确匹配 */
    private QueryNode buildNestedTermNode(QueryTemplate template, SemanticIR.Entity entity) {
        String keyword = template.getStrategy().getKeyword();
        String targetField = keyword != null ? keyword : template.getStrategy().getField();

        QueryNode.BoolNode boolQuery = new QueryNode.BoolNode();
        boolQuery.addMust(new QueryNode.TermNode(targetField, entity.getValue()));

        // 如果有数值条件（如 lab_item > 10）
        if (entity.getOperator() != null && entity.getNumericValue() != null
                && template.getStrategy().getValueField() != null) {
            QueryNode.RangeNode range = new QueryNode.RangeNode();
            range.setField(template.getStrategy().getValueField());
            switch (entity.getOperator()) {
                case "gt":  range.setGt(entity.getNumericValue()); break;
                case "gte": range.setGte(entity.getNumericValue()); break;
                case "lt":  range.setLt(entity.getNumericValue()); break;
                case "lte": range.setLte(entity.getNumericValue()); break;
                case "eq":  range.setGte(entity.getNumericValue());
                            range.setLte(entity.getNumericValue()); break;
            }
            boolQuery.addMust(range);
        }

        QueryNode.NestedNode nested = new QueryNode.NestedNode();
        nested.setPath(template.getStrategy().getTable());
        nested.setQuery(boolQuery);
        nested.setInnerHitsSize(0);
        return nested;
    }

    /** nested + match_phrase / should：嵌套表模糊匹配（支持同义词） */
    private QueryNode buildNestedMatchNode(QueryTemplate template, SemanticIR.Entity entity) {
        String value = entity.getValue();

        QueryNode.BoolNode should = new QueryNode.BoolNode();

        // 主字段 match_phrase
        should.addShould(new QueryNode.MatchPhraseNode(template.getStrategy().getField(), value));

        // keyword 字段 term
        if (template.getStrategy().getKeyword() != null) {
            should.addShould(new QueryNode.TermNode(template.getStrategy().getKeyword(), value));
        }

        // 同义词（如果模板启用）
        if (template.getStrategy().isUseSynonyms()) {
            for (String syn : OntologyHelper.getSynonyms(entity.getType(), value)) {
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

    /** range 查询：时间范围 */
    private QueryNode buildRangeNode(QueryTemplate template, SemanticIR.Entity entity) {
        // 不通过 entity 直接构造，由 ASTBuilder 传入时间约束
        return null;
    }

    /** 回退策略：generic match_phrase */
    private QueryNode buildFallback(SemanticIR.Entity entity) {
        return new QueryNode.MatchPhraseNode("total_src", entity.getValue());
    }

    // ===== 模板加载 =====

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

    /** 内置默认模板（当 templates.json 不存在时） */
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

    public List<QueryTemplate> getAllTemplates() { return allTemplates; }

    // ===== POJO =====

    static class TemplateFile {
        private String version;
        private List<QueryTemplate> templates;

        public String getVersion() { return version; }
        public void setVersion(String v) { this.version = v; }
        public List<QueryTemplate> getTemplates() { return templates; }
        public void setTemplates(List<QueryTemplate> t) { this.templates = t; }
    }

    public static class QueryTemplate {
        private String name;
        private String description;
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
            private String name;
            private String type;
            private String description;
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

        public static class Strategy {
            private String type;        // term | match_phrase | nested_term | nested_match | range
            private String table;       // nested 表名（仅 nested 类型）
            private String field;       // ES 字段路径
            private String keyword;     // keyword 子字段路径
            private String valueField;  // 数值字段（如检验结果值）
            private boolean useSynonyms;
            private String dateFormat;  // 时间格式模板

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

    // ===== OntologyHelper (移植自 StrategySelector) =====

    public static class OntologyHelper {
        private static final Map<String, Map<String, List<String>>> SYNONYMS = new HashMap<>();

        static {
            Map<String, List<String>> diseaseSyns = new HashMap<>();
            diseaseSyns.put("脑梗死", Arrays.asList("脑梗塞", "脑血栓", "缺血性脑卒中", "cerebral infarction"));
            diseaseSyns.put("糖尿病", Arrays.asList("diabetes", "DM", "2型糖尿病"));
            diseaseSyns.put("高血压", Arrays.asList("hypertension", "原发性高血压"));
            diseaseSyns.put("冠心病", Arrays.asList("冠状动脉粥样硬化性心脏病", "coronary heart disease"));
            SYNONYMS.put("disease", diseaseSyns);
        }

        public static List<String> getSynonyms(String type, String value) {
            Map<String, List<String>> typeMap = SYNONYMS.getOrDefault(type, Collections.emptyMap());
            List<String> result = new ArrayList<>(typeMap.getOrDefault(value, Collections.emptyList()));
            // 去重
            result.remove(value);
            return result;
        }

        public static void addSynonym(String type, String term, String synonym) {
            SYNONYMS.computeIfAbsent(type, k -> new HashMap<>())
                    .computeIfAbsent(term, k -> new ArrayList<>())
                    .add(synonym);
        }
    }
}
