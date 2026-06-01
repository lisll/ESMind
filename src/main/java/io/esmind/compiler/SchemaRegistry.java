package io.esmind.compiler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schema Registry — 字段元数据注册表。
 * 启动时从 ES mapping 加载并缓存到本地 JSON。
 * 运行时不再请求 ES。
 */
public class SchemaRegistry {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    /** Schema 版本标识 */
    private static final String SCHEMA_VERSION = "1.0.0";

    private final String indexName;
    private final String cacheFilePath;

    /** fieldName → SchemaField */
    private final Map<String, SchemaField> fieldsByName = new ConcurrentHashMap<>();
    /** bizName → fieldName */
    private final Map<String, String> bizNameToField = new ConcurrentHashMap<>();
    /** semantic_type → SchemaField 列表（未来支持） */
    private final Map<String, List<SchemaField>> fieldsByType = new ConcurrentHashMap<>();
    /** nestedPath → 该表下的 SchemaField 列表 */
    private final Map<String, List<SchemaField>> fieldsByNestedPath = new ConcurrentHashMap<>();

    public SchemaRegistry(String indexName, String cacheFilePath) {
        this.indexName = indexName;
        this.cacheFilePath = cacheFilePath;
    }

    public void register(SchemaField field) {
        fieldsByName.put(field.getFieldName(), field);

        if (field.getBizNames() != null) {
            for (String bizName : field.getBizNames()) {
                bizNameToField.put(bizName, field.getFieldName());
            }
        }

        if (field.getNestedPath() != null) {
            fieldsByNestedPath.computeIfAbsent(field.getNestedPath(), k -> new ArrayList<>()).add(field);
        }
    }

    public void registerAll(List<SchemaField> fields) {
        for (SchemaField f : fields) register(f);
        log.info("SchemaRegistry: {} fields, {} bizNames, {} nested tables",
                fieldsByName.size(), bizNameToField.size(), fieldsByNestedPath.size());
    }

    /** 按 ES 字段路径查找 */
    public SchemaField getByFieldName(String fieldName) {
        return fieldsByName.get(fieldName);
    }

    /** 按中文业务名称查找 */
    public SchemaField getByBizName(String bizName) {
        String fn = bizNameToField.get(bizName);
        return fn != null ? fieldsByName.get(fn) : null;
    }

    /** 模糊查找业务名称 */
    public SchemaField fuzzyLookupBizName(String text) {
        // 1. 精确匹配
        SchemaField exact = getByBizName(text);
        if (exact != null) return exact;
        // 2. 包含匹配
        for (Map.Entry<String, String> e : bizNameToField.entrySet()) {
            if (e.getKey().contains(text) || text.contains(e.getKey())) {
                return fieldsByName.get(e.getValue());
            }
        }
        return null;
    }

    /** 按 semantic_type 查找（disease/lab_item → 对应字段） */
    public SchemaField getBySemanticType(String semanticType) {
        // 硬编码 semantic_type → ES 业务表映射
        switch (semanticType) {
            case "disease":
                // 优先 shouyezhenduan.diagnosis_name
                SchemaField f = getByFieldName("shouyezhenduan.diagnosis_name");
                if (f != null) return f;
                // fallback
                return fuzzyLookupBizName("诊断名称");
            case "lab_item":
                f = getByFieldName("jianyanbaogaofu.lab_sub_item_name");
                if (f != null) return f;
                return fuzzyLookupBizName("检验项目");
            case "department":
                return fuzzyLookupBizName("科室名称");
            case "symptom":
                return fuzzyLookupBizName("症状");
            case "medicine":
                f = getByFieldName("yizhu.order_item_name");
                if (f != null) return f;
                return fuzzyLookupBizName("医嘱项目");
            case "patient_id":
                f = getByFieldName("patient.patient_id");
                if (f != null) return f;
                return fuzzyLookupBizName("患者ID");
            default:
                return null;
        }
    }

    /** 获取 nested 表下的所有字段 */
    public List<SchemaField> getFieldsByNestedPath(String path) {
        return fieldsByNestedPath.getOrDefault(path, Collections.emptyList());
    }

    public Set<String> getNestedPaths() { return fieldsByNestedPath.keySet(); }
    public Collection<SchemaField> getAllFields() { return fieldsByName.values(); }
    public String getIndexName() { return indexName; }
    public int size() { return fieldsByName.size(); }

    /** 获取所有顶层业务表的名称列表 */
    public List<String> getTopLevelTableNames() {
        List<String> result = new ArrayList<>();
        for (SchemaField f : fieldsByName.values()) {
            if (isTopLevelTable(f)) {
                result.add(f.getFieldName());
            }
        }
        return result;
    }

    /**
     * 通过中文别名查找顶层业务表（nested/object 表名）。
     * 匹配优先级：精确bizName → 包含bizName → 字段名包含alias → 字段名被alias包含
     */
    public String findTableByAlias(String alias) {
        if (alias == null || alias.isEmpty()) return null;
        // 1. 精确匹配 bizName
        SchemaField exact = getByBizName(alias);
        if (exact != null && isTopLevelTable(exact)) return exact.getFieldName();
        // 2. 模糊匹配 bizName
        SchemaField fuzzy = fuzzyLookupBizName(alias);
        if (fuzzy != null && isTopLevelTable(fuzzy)) return fuzzy.getFieldName();
        // 3. 在字段名中搜索包含关系（nested/object 表名）
        for (SchemaField f : getAllFields()) {
            String fn = f.getFieldName();
            if (isTopLevelTable(f)) {
                if (fn.contains(alias) || alias.contains(fn)) return fn;
            }
        }
        // 4. 搜索子字段的 bizName（如 "病案首页" 可能对应 binganshouye.admission_time 的 bizName）
        for (SchemaField f : getAllFields()) {
            if (f.getBizNames() != null) {
                for (String bn : f.getBizNames()) {
                    if (bn.contains(alias) || alias.contains(bn)) {
                        String tbl = extractTableName(f.getFieldName());
                        if (tbl != null) return tbl;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 获取某个业务表下可用的 date 字段列表。
     * 返回字段名（含表前缀，如 binganshouye.admission_time）
     */
    public List<String> getDateFieldsForTable(String tableName) {
        List<String> result = new ArrayList<>();
        // 先查 nested 表下的字段
        if (fieldsByNestedPath.containsKey(tableName)) {
            for (SchemaField f : fieldsByNestedPath.get(tableName)) {
                if (f.isDateField()) result.add(f.getFieldName());
            }
        }
        // 再查整个 registry 中属于该表前缀的 date 字段
        String prefix = tableName + ".";
        for (SchemaField f : fieldsByName.values()) {
            if (f.isDateField() && f.getFieldName().startsWith(prefix)) {
                if (!result.contains(f.getFieldName())) result.add(f.getFieldName());
            }
        }
        return result;
    }

    /** 判断是否为顶层业务表（nested 或 object 类型的顶级字段） */
    public boolean isTopLevelTable(SchemaField f) {
        if (f == null) return false;
        if (f.getFieldName() == null || f.getFieldName().contains(".")) return false;
        // 顶层表：字段名不含点号（非子字段）且类型是 nested 或 object
        // 注意：SchemaLoader 对 nested/object 表自身也设了 nestedPath=self，
        //       所以不能依赖 nestedPath==null，改用 字段名是否包含. 来判断
        return "nested".equals(f.getType()) || "object".equals(f.getType());
    }

    /** 从完整字段路径提取表名（第一个 . 之前的部分） */
    private String extractTableName(String fieldPath) {
        if (fieldPath == null) return null;
        int dot = fieldPath.indexOf('.');
        return dot > 0 ? fieldPath.substring(0, dot) : fieldPath;
    }

    // ===== 缓存 =====

    public void saveToCache() throws Exception {
        CacheData data = new CacheData();
        data.version = SCHEMA_VERSION;
        data.indexName = indexName;
        data.fields = new ArrayList<>(fieldsByName.values());
        File f = new File(cacheFilePath);
        f.getParentFile().mkdirs();
        MAPPER.writeValue(f, data);
        log.info("Schema cache saved: {} ({} fields)", cacheFilePath, data.fields.size());
    }

    public boolean loadFromCache() throws Exception {
        File f = new File(cacheFilePath);
        if (!f.exists()) return false;
        CacheData data = MAPPER.readValue(f, CacheData.class);
        if (!SCHEMA_VERSION.equals(data.version)) return false;
        registerAll(data.fields);
        log.info("Schema cache loaded: {} ({} fields)", cacheFilePath, data.fields.size());
        return true;
    }

    static class CacheData {
        public String version;
        public String indexName;
        public List<SchemaField> fields;
    }
}
