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
