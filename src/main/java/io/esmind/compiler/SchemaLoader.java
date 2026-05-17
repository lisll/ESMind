package io.esmind.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 从 ES mapping 加载字段元数据到 SchemaRegistry。
 * 只会被调用一次（首次启动时），之后从缓存加载。
 */
public class SchemaLoader {

    private static final Logger log = LoggerFactory.getLogger(SchemaLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String indexName;
    private final EsRestClient esClient;

    public SchemaLoader(String indexName, EsRestClient esClient) {
        this.indexName = indexName;
        this.esClient = esClient;
    }

    public SchemaRegistry load() throws Exception {
        SchemaRegistry registry = new SchemaRegistry(indexName, "data/schema-cache-" + indexName.hashCode() + ".json");

        // 尝试从缓存加载
        if (registry.loadFromCache()) {
            log.info("Schema loaded from cache for: {}", indexName);
            return registry;
        }

        // 从 ES 加载
        log.info("Loading schema from ES: {}", indexName);
        String mappingJson = esClient.getMapping(indexName);
        JsonNode root = MAPPER.readTree(mappingJson);

        JsonNode properties = extractProperties(root, indexName);
        if (properties == null) {
            throw new IllegalStateException("Cannot find properties in mapping for: " + indexName);
        }

        List<SchemaField> fields = new ArrayList<>();
        parseProperties("", properties, fields, null);

        registry.registerAll(fields);

        // 写缓存
        try { registry.saveToCache(); }
        catch (Exception e) { log.warn("Failed to save schema cache: {}", e.getMessage()); }

        return registry;
    }

    private void parseProperties(String prefix, JsonNode props, List<SchemaField> fields, String currentNested) {
        if (props == null) return;
        Iterator<Map.Entry<String, JsonNode>> iter = props.fields();
        while (iter.hasNext()) {
            Map.Entry<String, JsonNode> entry = iter.next();
            String name = entry.getKey();
            JsonNode def = entry.getValue();

            String fullPath = prefix.isEmpty() ? name : prefix + "." + name;
            String type = def.has("type") ? def.get("type").asText() : "object";
            boolean isNested = "nested".equals(type);
            boolean isObject = "object".equals(type) || (!def.has("type") && def.has("properties"));

            String nestedPath = isNested ? fullPath : currentNested;

            // 找 keyword 子字段
            String keywordField = null;
            if (def.has("fields")) {
                JsonNode subs = def.get("fields");
                for (String suffix : new String[]{".keyword", ".accurate", ".raw"}) {
                    String subName = suffix.substring(1);
                    if (subs.has(subName)) {
                        keywordField = fullPath + suffix;
                        break;
                    }
                }
            }
            if ("keyword".equals(type)) keywordField = fullPath;

            boolean isDate = "date".equals(type);
            boolean isNumeric = type != null && ("integer".equals(type) || "long".equals(type)
                    || "float".equals(type) || "double".equals(type));
            boolean aggregatable = "keyword".equals(type) || isNumeric || isDate;
            if (def.has("fields")) {
                Iterator<String> sn = def.get("fields").fieldNames();
                while (sn.hasNext()) {
                    String sfName = sn.next();
                    JsonNode sfNode = def.get("fields").get(sfName);
                    String st = sfNode.has("type") ? sfNode.get("type").asText() : "";
                    if ("keyword".equals(st)) aggregatable = true;
                }
            }

            List<String> bizNames = generateBizNames(name, fullPath);

            SchemaField field = new SchemaField(fullPath, bizNames, fullPath,
                    nestedPath, type, type, keywordField, isDate, isNumeric, aggregatable);
            fields.add(field);

            if ((isNested || isObject) && def.has("properties")) {
                parseProperties(fullPath, def.get("properties"), fields, nestedPath);
            }
        }
    }

    private List<String> generateBizNames(String name, String fullPath) {
        List<String> names = new ArrayList<>();
        names.add(name.replace("_", " "));
        if (fullPath.contains("diagnosis_name") || fullPath.contains("zhenduan")) {
            names.add("诊断名称"); names.add("诊断");
        } else if (fullPath.contains("admission_time") || fullPath.contains("ruyuanriqi")) {
            names.add("入院时间"); names.add("就诊时间");
        } else if (fullPath.contains("discharge_time") || fullPath.contains("chuyuanriqi")) {
            names.add("出院时间");
        } else if (fullPath.contains("patient_id")) {
            names.add("患者ID"); names.add("患者编号");
        } else if (fullPath.contains("lab_sub_item_name") || fullPath.contains("jianyan")) {
            names.add("检验项目"); names.add("检验细项");
        } else if (fullPath.contains("exam_item_name") || fullPath.contains("jiancha")) {
            names.add("检查项目");
        } else if (fullPath.contains("dept_name")) {
            names.add("科室名称"); names.add("科室");
        } else if (fullPath.contains("order_item_name") || fullPath.contains("yizhu")) {
            names.add("医嘱项目");
        } else if (fullPath.contains("symptom") || fullPath.contains("zhengzhuang")) {
            names.add("症状");
        }
        return names;
    }

    /** 兼容 ES 6.x 和 7.x+ 的 properties 提取 */
    private JsonNode extractProperties(JsonNode root, String indexName) {
        JsonNode idx = root.get(indexName);
        if (idx == null) return null;
        JsonNode mappings = idx.get("mappings");
        if (mappings == null) return null;
        JsonNode props = mappings.get("properties");
        if (props != null) return props;
        // ES 6.x
        Iterator<String> it = mappings.fieldNames();
        while (it.hasNext()) {
            JsonNode tn = mappings.get(it.next());
            if (tn.isObject() && tn.has("properties")) return tn.get("properties");
        }
        return null;
    }
}
