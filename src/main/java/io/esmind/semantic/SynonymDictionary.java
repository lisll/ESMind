package io.esmind.semantic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 同义词典 — 从 JSON 文件加载并支持热重载。
 *
 * <p>设计来源：Vanna AI 的 feedback loop（question→SQL→feedback→train）。
 * 核心差异：Vanna 通过训练更新 schema，我们通过反馈更新同义词。
 *
 * <p>三层检索策略：
 * <ol>
 *   <li><b>精确匹配</b> — term→synonyms 完全一致</li>
 *   <li><b>包含匹配</b> — term 或 synonyms 包含用户输入的词</li>
 *   <li><b>通配匹配</b> — 返回原值（fallback）</li>
 * </ol>
 *
 * <p>热重载：每次 getSynonyms() 检查文件 lastModified 时间戳，
 * 仅在文件被修改时重新加载。生产环境中无需重启。
 */
public class SynonymDictionary {

    private static final Logger log = LoggerFactory.getLogger(SynonymDictionary.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 默认词典文件路径（classpath 和文件系统） */
    private static final String DEFAULT_CLASSPATH = "/synonyms.json";
    private static final String DEFAULT_FILE = "data/synonyms.json";

    /** type → term → synonyms 列表 */
    private final Map<String, Map<String, List<String>>> synonymsByType = new ConcurrentHashMap<>();

    /** 文件路径（null = 只使用 classpath 默认） */
    private final String filePath;

    /** 上次加载时的文件修改时间（0 = 未加载） */
    private volatile long lastModified = 0;

    /** 读写锁，保证线程安全 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 单例（如果使用默认路径） */
    private static volatile SynonymDictionary instance;

    /**
     * 获取默认单例。
     */
    public static SynonymDictionary getDefault() {
        if (instance == null) {
            synchronized (SynonymDictionary.class) {
                if (instance == null) {
                    instance = new SynonymDictionary(DEFAULT_FILE);
                }
            }
        }
        return instance;
    }

    /**
     * 重置单例（用于测试）。
     */
    static void resetDefault() {
        instance = null;
    }

    public SynonymDictionary() {
        this.filePath = DEFAULT_FILE;
        loadAll();
    }

    public SynonymDictionary(String filePath) {
        this.filePath = filePath != null ? filePath : DEFAULT_FILE;
        loadAll();
    }

    // ===== 主查询方法 =====

    /**
     * 获取指定类型和值的同义词列表。
     * 自动触发热重载检查。
     */
    public List<String> getSynonyms(String type, String value) {
        hotReloadIfNeeded();

        lock.readLock().lock();
        try {
            Map<String, List<String>> dict = synonymsByType.get(type);
            if (dict == null) {
                return Collections.singletonList(value);
            }

            // 1. 精确匹配
            if (dict.containsKey(value)) {
                return dict.get(value);
            }

            // 2. 包含匹配
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
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 运行时添加同义词（写入内存）。
     * 不会自动持久化到文件，需调用 saveToFile()。
     */
    public void addSynonym(String type, String term, String synonym) {
        lock.writeLock().lock();
        try {
            synonymsByType
                    .computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(term, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(synonym);
            log.info("Added synonym: {} / {} → {}", type, term, synonym);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 批量添加同义词。
     */
    public void addSynonyms(String type, String term, List<String> synonyms) {
        if (synonyms == null || synonyms.isEmpty()) return;
        lock.writeLock().lock();
        try {
            List<String> existing = synonymsByType
                    .computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(term, k -> Collections.synchronizedList(new ArrayList<>()));
            for (String syn : synonyms) {
                if (!existing.contains(syn)) {
                    existing.add(syn);
                }
            }
            log.info("Added {} synonyms for {} / {}", synonyms.size(), type, term);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 持久化到文件。
     */
    public synchronized void saveToFile() throws IOException {
        lock.readLock().lock();
        try {
            File f = new File(filePath);
            f.getParentFile().mkdirs();
            MAPPER.writeValue(f, buildFileData());
            lastModified = f.lastModified();
            log.info("Synonym dictionary saved: {} ({} types)", filePath, synonymsByType.size());
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== 内部方法 =====

    /** 热重载检查 */
    private void hotReloadIfNeeded() {
        if (filePath == null) return;
        File f = new File(filePath);
        if (!f.exists()) return;

        long modified = f.lastModified();
        if (modified > lastModified) {
            log.info("Synonym dictionary file changed, reloading: {}", filePath);
            loadFromFile(f);
            lastModified = modified;
        }
    }

    /** 加载所有来源：classpath 默认 + 文件系统文件 */
    private void loadAll() {
        // 1. Load built-in defaults from classpath
        loadBuiltinDefaults();

        // 2. Load from file (overrides/defaults)
        if (filePath != null) {
            File f = new File(filePath);
            if (f.exists()) {
                loadFromFile(f);
                lastModified = f.lastModified();
                log.info("Loaded synonym dictionary from file: {}", filePath);
            } else {
                // Try saving defaults to file for first use
                try {
                    saveToFile();
                } catch (IOException e) {
                    log.warn("Cannot save default synonym file: {}", e.getMessage());
                }
            }
        }
    }

    /** 从文件加载 */
    private void loadFromFile(File f) {
        try {
            FileData data = MAPPER.readValue(f, FileData.class);
            if (data.getSynonyms() != null) {
                lock.writeLock().lock();
                try {
                    // Merge: file content OVERRIDES built-in
                    for (Map.Entry<String, Map<String, List<String>>> typeEntry : data.getSynonyms().entrySet()) {
                        synonymsByType.put(typeEntry.getKey(), new ConcurrentHashMap<>(typeEntry.getValue()));
                    }
                } finally {
                    lock.writeLock().unlock();
                }
            }
            log.info("Loaded {} synonym types from {}", synonymsByType.size(), f);
        } catch (Exception e) {
            log.error("Failed to load synonym file: {}", f, e);
        }
    }

    /** 内置默认同义词（作为 classpath 回退） */
    private void loadBuiltinDefaults() {
        // Try classpath first
        try (InputStream is = getClass().getResourceAsStream(DEFAULT_CLASSPATH)) {
            if (is != null) {
                FileData data = MAPPER.readValue(is, FileData.class);
                if (data.getSynonyms() != null) {
                    lock.writeLock().lock();
                    try {
                        for (Map.Entry<String, Map<String, List<String>>> typeEntry : data.getSynonyms().entrySet()) {
                            synonymsByType.put(typeEntry.getKey(), new ConcurrentHashMap<>(typeEntry.getValue()));
                        }
                    } finally {
                        lock.writeLock().unlock();
                    }
                    log.info("Loaded synonym dictionary from classpath: {}", DEFAULT_CLASSPATH);
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("No classpath synonym file found: {}", e.getMessage());
        }

        // Fallback: hardcoded defaults
        loadHardcodedDefaults();
    }

    /** 硬编码默认值（当 classpath 和文件都不存在时） */
    private void loadHardcodedDefaults() {
        lock.writeLock().lock();
        try {
            // Disease
            Map<String, List<String>> disease = new LinkedHashMap<>();
            disease.put("脑梗死", Arrays.asList("脑梗死", "脑卒中", "脑梗", "脑血栓", "cerebral infarction", "脑梗塞"));
            disease.put("糖尿病", Arrays.asList("糖尿病", "diabetes", "DM", "2型糖尿病"));
            disease.put("高血压", Arrays.asList("高血压", "hypertension", "高血压病"));
            disease.put("冠心病", Arrays.asList("冠心病", "冠状动脉粥样硬化性心脏病", "coronary heart disease", "CHD"));
            disease.put("心肌梗死", Arrays.asList("心肌梗死", "心梗", "急性心肌梗死", "myocardial infarction"));
            synonymsByType.put("disease", new ConcurrentHashMap<>(disease));

            // Lab items
            Map<String, List<String>> lab = new LinkedHashMap<>();
            lab.put("白细胞", Arrays.asList("白细胞", "WBC", "白细胞计数", "White Blood Cell"));
            lab.put("红细胞", Arrays.asList("红细胞", "RBC", "红细胞计数"));
            lab.put("血糖", Arrays.asList("血糖", "GLU", "葡萄糖", "blood glucose"));
            lab.put("血小板", Arrays.asList("血小板", "PLT", "血小板计数"));
            lab.put("C反应蛋白", Arrays.asList("C反应蛋白", "CRP", "hs-CRP"));
            lab.put("脑脊液", Arrays.asList("脑脊液", "CSF", "cerebrospinal fluid"));
            synonymsByType.put("lab_item", new ConcurrentHashMap<>(lab));

            // Department
            Map<String, List<String>> dept = new LinkedHashMap<>();
            dept.put("神经内科", Arrays.asList("神经内科", "神经科", "neurology"));
            dept.put("ICU", Arrays.asList("ICU", "重症监护室", "重症医学科", "intensive care unit"));
            dept.put("急诊科", Arrays.asList("急诊科", "急诊", "emergency"));
            synonymsByType.put("department", new ConcurrentHashMap<>(dept));

            log.info("Loaded hardcoded synonym defaults ({} types)", synonymsByType.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private FileData buildFileData() {
        FileData data = new FileData();
        data.setVersion("2.0.0");
        data.setSynonyms(new LinkedHashMap<>(synonymsByType));
        return data;
    }

    // ===== 数据类 =====

    static class FileData {
        private String version;
        private Map<String, Map<String, List<String>>> synonyms;

        public String getVersion() { return version; }
        public void setVersion(String v) { this.version = v; }
        public Map<String, Map<String, List<String>>> getSynonyms() { return synonyms; }
        public void setSynonyms(Map<String, Map<String, List<String>>> s) { this.synonyms = s; }
    }
}
