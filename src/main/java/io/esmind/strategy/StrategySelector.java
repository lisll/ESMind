package io.esmind.strategy;

import io.esmind.ast.QueryNode;
import io.esmind.compiler.SchemaField;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.semantic.SemanticIR;

import java.util.*;

/**
 * 语义类型 → 检索策略选择器。
 * 全部代码化，LLM 不参与检索策略决策。
 *
 * <p>策略定义：
 * <ul>
 *   <li>disease → nested + synonym match_phrase (shouyezhenduan)</li>
 *   <li>lab_item → nested + term (jianyanbaogaofu)</li>
 *   <li>department → keyword term</li>
 *   <li>patient_id → term</li>
 *   <li>symptom → match_phrase (在文书中搜索)</li>
 *   <li>medicine → nested + term (yizhu)</li>
 *   <li>time_range → range (binganshouye.admission_time)</li>
 * </ul>
 */
public class StrategySelector {

    private final SchemaRegistry schema;

    public StrategySelector(SchemaRegistry schema) {
        this.schema = schema;
    }

    /**
     * 根据 entity type 将 SemanticIR.Entity 转为 AST QueryNode。
     */
    public QueryNode buildNode(SemanticIR.Entity entity, int order) {
        String type = entity.getType();
        String value = entity.getValue();

        switch (type) {
            case "disease":
                return buildDiseaseStrategy(value);

            case "lab_item":
                return buildLabItemStrategy(value, entity.getOperator(), entity.getNumericValue());

            case "department":
                return buildDepartmentStrategy(value);

            case "patient_id":
                return buildPatientIdStrategy(value);

            case "symptom":
                return buildSymptomStrategy(value);

            case "medicine":
                return buildMedicineStrategy(value);

            default:
                // fallback: generic match
                SchemaField field = schema.getBySemanticType(type);
                if (field != null) {
                    if (field.isNested()) {
                        return buildNestedStrategy(field, value);
                    }
                    return new QueryNode.MatchPhraseNode(field.getMatchField(), value);
                }
                throw new IllegalArgumentException("Unknown semantic type: " + type);
        }
    }

    /** 疾病策略：nested + synonym match_phrase */
    private QueryNode buildDiseaseStrategy(String value) {
        // 目标表：shouyezhenduan（nested）
        SchemaField diagName = schema.getBySemanticType("disease");
        if (diagName == null) {
            throw new IllegalStateException("Cannot find disease field in schema");
        }

        // 同义词列表
        List<String> synonyms = OntologyHelper.getSynonyms("disease", value);
        if (synonyms.isEmpty()) {
            synonyms = Collections.singletonList(value);
        }

        QueryNode.BoolNode should = new QueryNode.BoolNode();
        for (String syn : synonyms) {
            // 用 match_phrase 在主字段搜索
            should.addShould(new QueryNode.MatchPhraseNode(diagName.getMatchField(), syn));
            // 也用 term 在 keyword 字段搜索
            if (diagName.getKeywordField() != null) {
                should.addShould(new QueryNode.TermNode(diagName.getKeywordField(), syn));
            }
        }
        should.setMinimumShouldMatch(1);

        QueryNode.NestedNode nested = new QueryNode.NestedNode();
        nested.setPath(diagName.getNestedPath());
        nested.setQuery(should);
        // 默认不返回 inner_hits，除非用户明确要详情
        nested.setInnerHitsSize(0);

        return nested;
    }

    /** 检验项目策略：nested + term */
    private QueryNode buildLabItemStrategy(String itemName, String op, String numVal) {
        SchemaField labField = schema.getBySemanticType("lab_item");
        if (labField == null) {
            // fallback to generic match
            return new QueryNode.MatchPhraseNode("jianyanbaogaofu.lab_sub_item_name", itemName);
        }

        // 检验项目名称查询（term on keyword field）
        String termField = labField.getKeywordField() != null
                ? labField.getKeywordField() : labField.getEsPath();

        QueryNode.BoolNode boolQuery = new QueryNode.BoolNode();
        boolQuery.addMust(new QueryNode.TermNode(termField, itemName));
        boolQuery.setMinimumShouldMatch(1);

        // 如果有数值条件（如 >10），添加 range 查询
        if (op != null && numVal != null) {
            // 假设检验值字段是 lab_test_value
            SchemaField labValueField = schema.getByFieldName("jianyanbaogaofu.lab_test_value");
            if (labValueField != null && labValueField.isNumeric()) {
                QueryNode.RangeNode range = new QueryNode.RangeNode();
                range.setField(labValueField.getEsPath());
                switch (op) {
                    case "gt": range.setGt(numVal); break;
                    case "gte": range.setGte(numVal); break;
                    case "lt": range.setLt(numVal); break;
                    case "lte": range.setLte(numVal); break;
                }
                boolQuery.addMust(range);
            }
        }

        QueryNode.NestedNode nested = new QueryNode.NestedNode();
        nested.setPath(labField.getNestedPath());
        nested.setQuery(boolQuery);
        nested.setInnerHitsSize(0);

        return nested;
    }

    /** 科室策略：keyword term */
    private QueryNode buildDepartmentStrategy(String deptName) {
        SchemaField deptField = schema.getBySemanticType("department");
        if (deptField != null) {
            return new QueryNode.TermNode(deptField.getTermField(), deptName);
        }
        // 在 patient.dept.dept_name 中搜索
        SchemaField pDept = schema.getByFieldName("patient.dept.dept_name");
        if (pDept != null) {
            return new QueryNode.TermNode(pDept.getTermField(), deptName);
        }
        return new QueryNode.MatchPhraseNode("patient.dept.dept_name", deptName);
    }

    /** 患者ID策略：exact term */
    private QueryNode buildPatientIdStrategy(String patientId) {
        SchemaField pf = schema.getBySemanticType("patient_id");
        if (pf != null) {
            return new QueryNode.TermNode(pf.getTermField(), patientId);
        }
        return new QueryNode.TermNode("patient.patient_id", patientId);
    }

    /** 症状策略：全文 match_phrase（在 total_src 或文书字段中） */
    private QueryNode buildSymptomStrategy(String symptom) {
        return new QueryNode.MatchPhraseNode("total_src", symptom);
    }

    /** 药物策略：nested + term（yizhu） */
    private QueryNode buildMedicineStrategy(String medName) {
        SchemaField medField = schema.getBySemanticType("medicine");
        if (medField != null) {
            QueryNode.NestedNode nested = new QueryNode.NestedNode();
            nested.setPath(medField.getNestedPath());
            nested.setQuery(new QueryNode.TermNode(medField.getTermField(), medName));
            nested.setInnerHitsSize(0);
            return nested;
        }
        return new QueryNode.MatchPhraseNode("yizhu.order_item_name", medName);
    }

    /** 通用 nested 策略 */
    private QueryNode buildNestedStrategy(SchemaField field, String value) {
        QueryNode.NestedNode nested = new QueryNode.NestedNode();
        nested.setPath(field.getNestedPath());
        nested.setQuery(new QueryNode.MatchPhraseNode(field.getMatchField(), value));
        nested.setInnerHitsSize(0);
        return nested;
    }

    // ===== OntologyHelper (embedded for MVP) =====

    /**
     * 医疗同义词查询（简化版，内联到 StrategySelector）。
     * 后续可抽取为独立 OntologyResolver。
     */
    public static class OntologyHelper {
        private static final Map<String, List<String>> DISEASE_SYNONYMS = new HashMap<>();
        private static final Map<String, List<String>> LAB_SYNONYMS = new HashMap<>();
        private static final Map<String, List<String>> DEPT_SYNONYMS = new HashMap<>();

        static {
            // 疾病同义词
            DISEASE_SYNONYMS.put("脑梗死", Arrays.asList("脑梗死", "脑卒中", "脑梗", "脑血栓", "cerebral infarction", "脑梗塞"));
            DISEASE_SYNONYMS.put("糖尿病", Arrays.asList("糖尿病", "diabetes", "DM", "2型糖尿病"));
            DISEASE_SYNONYMS.put("高血压", Arrays.asList("高血压", "hypertension", "高血压病"));
            DISEASE_SYNONYMS.put("冠心病", Arrays.asList("冠心病", "冠状动脉粥样硬化性心脏病", "coronary heart disease", "CHD"));
            DISEASE_SYNONYMS.put("心肌梗死", Arrays.asList("心肌梗死", "心梗", "急性心肌梗死", "myocardial infarction"));

            // 检验同义词
            LAB_SYNONYMS.put("白细胞", Arrays.asList("白细胞", "WBC", "白细胞计数", "White Blood Cell"));
            LAB_SYNONYMS.put("红细胞", Arrays.asList("红细胞", "RBC", "红细胞计数"));
            LAB_SYNONYMS.put("血糖", Arrays.asList("血糖", "GLU", "葡萄糖", "blood glucose"));
            LAB_SYNONYMS.put("血小板", Arrays.asList("血小板", "PLT", "血小板计数"));
            LAB_SYNONYMS.put("C反应蛋白", Arrays.asList("C反应蛋白", "CRP", "hs-CRP"));
            LAB_SYNONYMS.put("脑脊液", Arrays.asList("脑脊液", "CSF", "cerebrospinal fluid"));

            // 科室同义词
            DEPT_SYNONYMS.put("神经内科", Arrays.asList("神经内科", "神经科", "neurology"));
            DEPT_SYNONYMS.put("ICU", Arrays.asList("ICU", "重症监护室", "重症医学科", "intensive care unit"));
            DEPT_SYNONYMS.put("急诊科", Arrays.asList("急诊科", "急诊", "emergency"));
        }

        public static List<String> getSynonyms(String type, String value) {
            Map<String, List<String>> dict;
            switch (type) {
                case "disease": dict = DISEASE_SYNONYMS; break;
                case "lab_item": dict = LAB_SYNONYMS; break;
                case "department": dict = DEPT_SYNONYMS; break;
                default: return Collections.singletonList(value);
            }
            // 先精确匹配
            if (dict.containsKey(value)) return dict.get(value);
            // 包含匹配
            for (Map.Entry<String, List<String>> e : dict.entrySet()) {
                if (e.getKey().contains(value) || value.contains(e.getKey())) {
                    return e.getValue();
                }
                for (String syn : e.getValue()) {
                    if (syn.contains(value) || value.contains(syn)) {
                        return e.getValue();
                    }
                }
            }
            return Collections.singletonList(value);
        }
    }
}
