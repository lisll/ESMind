package io.esmind.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * BusinessSemanticRegistry — 业务表语义注册表。
 * <p>
 * 从 table-semantic.yaml 加载 20+ 核心业务表的语义配置。
 * 运行时提供：
 * <ul>
 *   <li>中英文别名 → 业务表名模糊匹配</li>
 *   <li>业务表名前缀 → categories 归类</li>
 *   <li>业务表名 → dateFields 列表</li>
 *   <li>找不到的表 → 返回 null，由调用方走 SchemaRegistry 兜底</li>
 * </ul>
 * <p>
 * 设计原则：新增业务表只改 YAML，不动 Java 代码。
 */
public class BusinessSemanticRegistry {

    private static final Logger log = LoggerFactory.getLogger(BusinessSemanticRegistry.class);

    /** 配置文件名（classpath 根目录） */
    private static final String CONFIG_PATH = "table-semantic.yaml";

    /** 表名 → 语义配置 */
    private final Map<String, TableSemantic> tableConfigs = new LinkedHashMap<>();

    /** 别名 → 表名（所有 alias 展开为单个映射） */
    private final Map<String, String> aliasToTable = new LinkedHashMap<>();

    /** 所有已注册的业务表名列表（有序） */
    private final List<String> tableNames = new ArrayList<>();

    /** 加载状态 */
    private boolean loaded = false;

    /**
     * 默认构造 — 从 classpath 自动加载 YAML。
     */
    public BusinessSemanticRegistry() {
        loadFromClasspath();
    }

    /**
     * 加载 YAML 配置。
     */
    private void loadFromClasspath() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (is == null) {
                log.warn("{} not found on classpath, registry will be empty", CONFIG_PATH);
                return;
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            ConfigFile config = mapper.readValue(is, ConfigFile.class);

            if (config.getTables() == null || config.getTables().isEmpty()) {
                log.warn("No tables defined in {}", CONFIG_PATH);
                return;
            }

            for (Map.Entry<String, TableSemantic> entry : config.getTables().entrySet()) {
                String tableName = entry.getKey();
                TableSemantic semantic = entry.getValue();
                semantic.setTableName(tableName);

                tableConfigs.put(tableName, semantic);
                tableNames.add(tableName);

                // 注册别名
                if (semantic.getAliases() != null) {
                    for (String alias : semantic.getAliases()) {
                        if (alias != null && !alias.isEmpty()) {
                            aliasToTable.put(alias, tableName);
                        }
                    }
                }
                // 自身表名也算一个别名
                aliasToTable.put(tableName, tableName);
            }

            loaded = true;
            log.info("BusinessSemanticRegistry loaded: {} tables, {} aliases",
                    tableConfigs.size(), aliasToTable.size());
        } catch (Exception e) {
            log.error("Failed to load {}", CONFIG_PATH, e);
        }
    }

    // ========================================================================
    // 查询接口
    // ========================================================================

    /**
     * 按别名查找业务表名。
     * 匹配优先级：精确别名 → 包含别名 → 包含别名片段
     */
    public String findTableByAlias(String alias) {
        if (alias == null || alias.isEmpty()) return null;

        // 1. 精确匹配
        String exact = aliasToTable.get(alias);
        if (exact != null) return exact;

        // 2. 包含匹配（别名字段包含输入，或输入包含别名）
        for (Map.Entry<String, String> e : aliasToTable.entrySet()) {
            if (e.getKey().contains(alias) || alias.contains(e.getKey())) {
                return e.getValue();
            }
        }

        return null;
    }

    /**
     * 获取表的语义配置。
     */
    public TableSemantic getTableSemantic(String tableName) {
        return tableConfigs.get(tableName);
    }

    /**
     * 获取业务名称（展现用）。
     */
    public String getBusinessName(String tableName) {
        TableSemantic s = tableConfigs.get(tableName);
        return s != null ? s.getBusinessName() : tableName;
    }

    /**
     * 获取分类列表。
     */
    public List<String> getCategories(String tableName) {
        TableSemantic s = tableConfigs.get(tableName);
        return s != null ? s.getCategories() : Collections.emptyList();
    }

    /**
     * 获取日期字段列表。
     */
    public List<String> getDateFields(String tableName) {
        TableSemantic s = tableConfigs.get(tableName);
        return s != null ? s.getDateFields() : Collections.emptyList();
    }

    /**
     * 获取所有已注册的表名。
     */
    public List<String> getTableNames() {
        return Collections.unmodifiableList(tableNames);
    }

    /**
     * 获取所有已注册的语义配置。
     */
    public Map<String, TableSemantic> getTableConfigs() {
        return Collections.unmodifiableMap(tableConfigs);
    }

    /**
     * 检查是否已加载配置。
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * 获取注册表大小。
     */
    public int size() {
        return tableConfigs.size();
    }

    // ========================================================================
    // 重新加载
    // ========================================================================

    /**
     * 重新从 classpath 加载 YAML 配置。
     * 在开发模式下（mvn spring-boot:run），写入 target/classes/ 后调用此方法即可立即生效。
     * 生产模式下（jar 内），需要重启。
     */
    public synchronized void reload() {
        log.info("BusinessSemanticRegistry reloading...");
        tableConfigs.clear();
        aliasToTable.clear();
        tableNames.clear();
        loaded = false;
        loadFromClasspath();
        log.info("BusinessSemanticRegistry reloaded: {} tables, {} aliases",
                tableConfigs.size(), aliasToTable.size());
    }

    /**
     * 运行时注册一张业务表（热添加，不依赖于文件重读）。
     * 用于 SemanticCatalog 确认后立即生效。
     */
    public synchronized void registerTable(String tableName, String businessName,
                                            List<String> aliases, List<String> categories,
                                            List<String> dateFields) {
        if (tableConfigs.containsKey(tableName)) {
            log.info("Table '{}' already registered, updating...", tableName);
        }

        TableSemantic semantic = new TableSemantic();
        semantic.setTableName(tableName);
        semantic.setBusinessName(businessName);
        semantic.setAliases(aliases);
        semantic.setCategories(categories);
        semantic.setDateFields(dateFields);

        tableConfigs.put(tableName, semantic);
        if (!tableNames.contains(tableName)) {
            tableNames.add(tableName);
        }

        // 注册别名
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isEmpty()) {
                    aliasToTable.put(alias, tableName);
                }
            }
        }
        aliasToTable.put(tableName, tableName);

        log.info("BusinessSemanticRegistry: registered table '{}' (businessName={})", tableName, businessName);
    }

    // ========================================================================
    // POJO
    // ========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ConfigFile {
        private String index;
        private Map<String, TableSemantic> tables;

        public String getIndex() { return index; }
        public void setIndex(String index) { this.index = index; }
        public Map<String, TableSemantic> getTables() { return tables; }
        public void setTables(Map<String, TableSemantic> tables) { this.tables = tables; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TableSemantic {
        private String tableName;
        private String businessName;
        private List<String> aliases;
        private List<String> categories;
        private List<String> dateFields;

        public String getTableName() { return tableName; }
        public void setTableName(String name) { this.tableName = name; }
        public String getBusinessName() { return businessName; }
        public void setBusinessName(String name) { this.businessName = name; }
        public List<String> getAliases() { return aliases; }
        public void setAliases(List<String> aliases) { this.aliases = aliases; }
        public List<String> getCategories() { return categories; }
        public void setCategories(List<String> cats) { this.categories = cats; }
        public List<String> getDateFields() { return dateFields; }
        public void setDateFields(List<String> fields) { this.dateFields = fields; }
    }
}
