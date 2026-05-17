package io.esmind.compiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Schema Serializer — 将 ES mapping 压缩为 LLM 友好的紧凑格式。
 *
 * <p>设计来源：DB-GPT 的级联 schema 检索（cascading schema retrieval）。
 * LLM 不需要知道完整 200KB 的 mapping，只需要知道：
 * <ul>
 *   <li>有哪些业务表（nested paths）</li>
 *   <li>表里有用的业务字段（field name + Chinese name + type）</li>
 *   <li>字段在哪个表里（nested path）</li>
 * </ul>
 *
 * <p>三种输出模式：
 * <ol>
 *   <li><b>紧凑全文</b> — 约 2-3KB，适合首次系统 prompt</li>
 *   <li><b>按领域过滤</b> — 只输出指定领域的字段（如 diagnosis/lab/dept）</li>
 *   <li><b>按查询意图选择</b> — SchemaPicker 根据 NL query 判断需要哪些字段</li>
 * </ol>
 */
public class SchemaSerializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaSerializer.class);

    private final SchemaRegistry registry;

    public SchemaSerializer(SchemaRegistry registry) {
        this.registry = registry;
    }

    // ===== 模式 1: 紧凑全文 =====

    /**
     * 完整紧凑 schema（约 2-3KB）。
     * 格式：
     * <pre>
     * [业务表: shouyezhenduan]
     *   - shouyezhenduan.diagnosis_name (text) ≡ 诊断名称, 诊断
     *   - shouyezhenduan.diagnosis_code (keyword) ≡ 诊断编码
     *
     * [业务表: binganshouye]
     *   - binganshouye.admission_time (date) ≡ 入院时间, 就诊时间
     *   - binganshouye.visit_number (keyword) ≡ 就诊号
     * </pre>
     */
    public String toCompactString() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Schema: ").append(registry.getIndexName()).append("\n\n");

        // 先输出 object/root 级别字段
        Set<String> printedNested = new HashSet<>();
        List<SchemaField> rootFields = new ArrayList<>();

        for (SchemaField f : registry.getAllFields()) {
            if (f.isNested()) continue;
            if (f.getNestedPath() == null) {
                rootFields.add(f);
            }
        }

        if (!rootFields.isEmpty()) {
            sb.append("## 根级字段\n");
            for (SchemaField f : rootFields) {
                sb.append("  - ").append(formatFieldLine(f)).append("\n");
            }
            sb.append("\n");
        }

        // 按 nested path 分组输出
        Collection<String> nestedPaths = registry.getNestedPaths();
        for (String path : nestedPaths) {
            List<SchemaField> nestedFields = registry.getFieldsByNestedPath(path);
            if (nestedFields.isEmpty()) continue;

            String bizName = inferTableName(path);
            sb.append("## [").append(bizName).append("] ").append(path).append("\n");
            for (SchemaField f : nestedFields) {
                // 只显示叶字段（非 nested/object 自身）
                if (f.isNested() || "object".equals(f.getType())) continue;
                sb.append("  - ").append(formatFieldLine(f)).append("\n");
            }
            sb.append("\n");
        }

        String result = sb.toString();
        log.info("Compact schema: {} chars ({} fields)", result.length(), registry.size());
        return result;
    }

    // ===== 模式 2: 按领域过滤 =====

    /**
     * 按领域类型过滤 schema。
     * @param domainTypes 领域集合如 [diagnosis, lab, dept, medicine, symptom]
     */
    public String toDomainString(Set<String> domainTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Schema (filtered: ").append(String.join(", ", domainTypes)).append(")\n\n");

        for (String domain : domainTypes) {
            List<SchemaField> domainFields = pickFieldsForDomain(domain);
            if (domainFields.isEmpty()) continue;

            sb.append("## ").append(domainLabel(domain)).append("\n");
            for (SchemaField f : domainFields) {
                sb.append("  - ").append(formatFieldLine(f)).append("\n");
            }
            sb.append("\n");
        }

        log.info("Domain schema: {} chars for {}", sb.length(), domainTypes);
        return sb.toString();
    }

    // ===== 模式 3: 按业务表名 =====

    /**
     * 只输出指定 nested 表下的字段。
     */
    public String toTableString(String... tablePaths) {
        StringBuilder sb = new StringBuilder();
        for (String path : tablePaths) {
            List<SchemaField> fields = registry.getFieldsByNestedPath(path);
            if (fields.isEmpty()) {
                // 也尝试作为单表索引
                SchemaField single = registry.getByFieldName(path);
                if (single != null) fields = Collections.singletonList(single);
            }
            if (fields.isEmpty()) continue;

            sb.append("## ").append(inferTableName(path)).append("\n");
            for (SchemaField f : fields) {
                sb.append("  - ").append(formatFieldLine(f)).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ===== 辅助方法 =====

    private String formatFieldLine(SchemaField f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.getFieldName());
        if (f.getKeywordField() != null && !f.getKeywordField().equals(f.getFieldName())) {
            sb.append("/k=").append(f.getKeywordField());
        }
        sb.append(" (").append(f.getType()).append(")");
        if (f.isNested()) sb.append(" [nested]");
        if (f.isNumeric()) sb.append(" [num]");
        if (f.isDateField()) sb.append(" [date]");
        if (f.getBizNames() != null && !f.getBizNames().isEmpty()) {
            sb.append(" ≡ ").append(String.join(", ", f.getBizNames()));
        }
        return sb.toString();
    }

    /** 推断业务表中文名 */
    private String inferTableName(String nestedPath) {
        if (nestedPath == null) return "root";
        if (nestedPath.contains("shouyezhenduan")) return "首页诊断";
        if (nestedPath.contains("binganshouye")) return "病案首页";
        if (nestedPath.contains("jianyanbaogaofu")) return "检验报告附";
        if (nestedPath.contains("yizhu")) return "医嘱";
        if (nestedPath.contains("patient")) return "患者信息";
        if (nestedPath.contains("shoushujilu")) return "手术记录";
        if (nestedPath.contains("bingchengjilu")) return "病程记录";
        return nestedPath;
    }

    private String domainLabel(String domain) {
        switch (domain) {
            case "diagnosis": return "诊断 (诊断名称、诊断编码)";
            case "lab": return "检验 (检验项目、结果、范围)";
            case "dept": return "科室 (科室名称、病房)";
            case "medicine": return "药物/医嘱 (医嘱项目、药物)";
            case "patient": return "患者 (患者ID、姓名、基本信息)";
            case "symptom": return "症状 (主诉、症状描述)";
            case "surgery": return "手术 (手术名称、时间)";
            case "exam": return "检查 (检查项目、结果)";
            case "time": return "时间 (入院、出院、就诊时间)";
            default: return domain;
        }
    }

    /** 领域 → 字段路径前缀 映射 */
    private static final Map<String, List<String>> DOMAIN_PREFIXES = new LinkedHashMap<>();
    static {
        DOMAIN_PREFIXES.put("diagnosis", Arrays.asList("shouyezhenduan.", "zhenduan"));
        DOMAIN_PREFIXES.put("lab", Arrays.asList("jianyanbaogaofu.", "jianyan"));
        DOMAIN_PREFIXES.put("dept", Arrays.asList("dept", "keshi", "ward"));
        DOMAIN_PREFIXES.put("medicine", Arrays.asList("yizhu.", "medicine", "drug"));
        DOMAIN_PREFIXES.put("patient", Arrays.asList("patient.", "bingren"));
        DOMAIN_PREFIXES.put("symptom", Arrays.asList("symptom", "zhengzhuang", "zhusu"));
        DOMAIN_PREFIXES.put("surgery", Arrays.asList("shoushujilu.", "surgery"));
        DOMAIN_PREFIXES.put("exam", Arrays.asList("jianchabaogao.", "exam"));
        DOMAIN_PREFIXES.put("time", Arrays.asList("_time", "time", "riqi"));
    }

    /** 按领域选取相关字段 */
    private List<SchemaField> pickFieldsForDomain(String domain) {
        List<String> prefixes = DOMAIN_PREFIXES.getOrDefault(domain, Collections.singletonList(domain));
        List<SchemaField> result = new ArrayList<>();
        for (SchemaField f : registry.getAllFields()) {
            String name = f.getFieldName().toLowerCase();
            for (String prefix : prefixes) {
                if (name.contains(prefix.toLowerCase())) {
                    result.add(f);
                    break;
                }
            }
        }
        return result;
    }
}
