package io.esmind.semantic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 用户反馈管理器 — 将用户反馈转译为同义词典更新。
 *
 * <p>设计来源：Vanna AI 的 feedback loop。
 * Vanna 的流程：user question → generated SQL → user approval/correction → train on correction.
 * 我们的流程：user query → ES DSL → user feedback ("这个应该匹配XXX") → add synonym → persist.
 *
 * <p>使用场景：
 * <ul>
 *   <li>用户说"搜索脑梗死没找到脑梗塞的患者" → feedback → "脑梗塞"是"脑梗死"的同义词</li>
 *   <li>用户说"ICU查不到重症监护室的" → feedback → "重症监护室"是"ICU"的同义词</li>
 *   <li>用户说"WBC没搜到结果" → feedback → "WBC"是"白细胞"的同义词</li>
 * </ul>
 */
public class FeedbackManager {

    private static final Logger log = LoggerFactory.getLogger(FeedbackManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String FEEDBACK_FILE = "data/feedback.json";

    private final SynonymDictionary synonymDictionary;
    private final ConcurrentLinkedQueue<FeedbackRecord> pendingFeedback = new ConcurrentLinkedQueue<>();

    public FeedbackManager(SynonymDictionary synonymDictionary) {
        this.synonymDictionary = synonymDictionary;
        loadFeedbackHistory();
    }

    /**
     * 记录用户反馈并尝试自动生成同义词。
     *
     * @param nlQuery 用户的原始查询
     * @param entityType 实体类型（disease/lab_item/department 等）
     * @param userTerm 用户输入的值
     * @param missingTerm 用户认为应该匹配到的值
     */
    public FeedbackResult recordFeedback(String nlQuery, String entityType,
                                          String userTerm, String missingTerm) {
        // 1. 创建反馈记录
        FeedbackRecord record = new FeedbackRecord(
                UUID.randomUUID().toString(),
                nlQuery, entityType, userTerm, missingTerm,
                LocalDateTime.now().toString()
        );
        pendingFeedback.add(record);

        // 2. 自动衍生同义词
        boolean synonymAdded = autoDeriveSynonym(entityType, userTerm, missingTerm);

        // 3. 持久化
        persistFeedback(record);

        String message;
        if (synonymAdded) {
            log.info("Feedback processed + synonym added: {} → {} (type={})", userTerm, missingTerm, entityType);
            message = String.format("已将「%s」添加为「%s」的同义词，下次查询会覆盖更全。", missingTerm, userTerm);
        } else {
            log.info("Feedback recorded (no synonym action): {}", record.id);
            message = String.format("已记录反馈「%s」→「%s」，需要人工审核同义词映射。", userTerm, missingTerm);
        }

        return new FeedbackResult(record.id, message, synonymAdded);
    }

    /**
     * 自动衍生同义词。
     * 规则：将 missingTerm 作为 userTerm 的同义词添加。
     */
    private boolean autoDeriveSynonym(String type, String userTerm, String missingTerm) {
        if (userTerm == null || userTerm.isBlank() || missingTerm == null || missingTerm.isBlank()) {
            return false;
        }
        if (userTerm.equals(missingTerm)) return false;

        try {
            synonymDictionary.addSynonym(type, userTerm, missingTerm);
            synonymDictionary.saveToFile();
            return true;
        } catch (Exception e) {
            log.warn("Failed to persist synonym: {}", e.getMessage());
            return false;
        }
    }

    /** 持久化到 feedback.json */
    private void persistFeedback(FeedbackRecord record) {
        try {
            File f = new File(FEEDBACK_FILE);
            f.getParentFile().mkdirs();

            List<FeedbackRecord> existing = new ArrayList<>();
            if (f.exists()) {
                existing = MAPPER.readValue(f, new TypeReference<List<FeedbackRecord>>() {});
            }
            existing.add(record);

            // 只保留最近 1000 条
            if (existing.size() > 1000) {
                existing = existing.subList(existing.size() - 1000, existing.size());
            }

            MAPPER.writeValue(f, existing);
        } catch (Exception e) {
            log.warn("Failed to persist feedback: {}", e.getMessage());
        }
    }

    /** 启动时加载历史反馈（不做重放，仅审计） */
    private void loadFeedbackHistory() {
        try {
            File f = new File(FEEDBACK_FILE);
            if (f.exists()) {
                List<FeedbackRecord> history = MAPPER.readValue(f, new TypeReference<List<FeedbackRecord>>() {});
                log.info("Loaded {} feedback records from history", history.size());
            }
        } catch (Exception e) {
            log.debug("No feedback history found: {}", e.getMessage());
        }
    }

    // ===== 数据类 =====

    public static class FeedbackRecord {
        public String id;
        public String nlQuery;
        public String entityType;
        public String userTerm;
        public String missingTerm;
        public String timestamp;

        public FeedbackRecord() {}

        public FeedbackRecord(String id, String nlQuery, String entityType,
                              String userTerm, String missingTerm, String timestamp) {
            this.id = id;
            this.nlQuery = nlQuery;
            this.entityType = entityType;
            this.userTerm = userTerm;
            this.missingTerm = missingTerm;
            this.timestamp = timestamp;
        }
    }

    public static class FeedbackResult {
        public final String id;
        public final String message;
        public final boolean synonymAdded;

        public FeedbackResult(String id, String message, boolean synonymAdded) {
            this.id = id;
            this.message = message;
            this.synonymAdded = synonymAdded;
        }
    }
}
