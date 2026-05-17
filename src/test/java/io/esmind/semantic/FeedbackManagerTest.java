package io.esmind.semantic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FeedbackManager 单元测试。
 */
class FeedbackManagerTest {

    private SynonymDictionary dict;
    private FeedbackManager feedbackManager;

    @BeforeEach
    void setUp() {
        dict = new SynonymDictionary(null);
        feedbackManager = new FeedbackManager(dict);
    }

    @Test
    void testRecordFeedbackAddsSynonym() {
        // User says: "搜脑梗死没搜到脑梗塞的结果"
        FeedbackManager.FeedbackResult result = feedbackManager.recordFeedback(
                "脑梗死患者", "disease", "脑梗死", "脑梗塞"
        );

        assertNotNull(result);
        assertTrue(result.synonymAdded, "Synonym should be auto-added");

        // Verify the synonym was actually added to the dictionary
        var syns = dict.getSynonyms("disease", "脑梗死");
        assertTrue(syns.contains("脑梗塞"), "脑梗塞 should now be a synonym of 脑梗死");
    }

    @Test
    void testRecordFeedbackSameTerm() {
        // no-op: userTerm == missingTerm
        FeedbackManager.FeedbackResult result = feedbackManager.recordFeedback(
                "test", "disease", "相同词", "相同词"
        );
        assertFalse(result.synonymAdded, "Should not add synonym when terms are identical");
    }

    @Test
    void testRecordFeedbackEmptyTerms() {
        FeedbackManager.FeedbackResult result = feedbackManager.recordFeedback(
                "test", "lab_item", "", "值"
        );
        assertFalse(result.synonymAdded, "Should not add synonym for empty term");
    }

    @Test
    void testRecordFeedbackGeneratesId() {
        var r1 = feedbackManager.recordFeedback("q1", "disease", "高血压", "hypertension");
        var r2 = feedbackManager.recordFeedback("q2", "lab_item", "WBC", "白细胞");

        assertNotNull(r1.id);
        assertNotNull(r2.id);
        assertNotEquals(r1.id, r2.id, "Each feedback should have unique ID");
    }

    @Test
    void testMultipleFeedbackRoundTrip() {
        // Record multiple feedbacks
        feedbackManager.recordFeedback("q1", "disease", "冠心病", "CHD");
        feedbackManager.recordFeedback("q2", "lab_item", "血糖", "GLU");

        // Verify all synonyms persisted
        var disease = dict.getSynonyms("disease", "冠心病");
        assertTrue(disease.contains("CHD"));

        var lab = dict.getSynonyms("lab_item", "血糖");
        assertTrue(lab.contains("GLU"));
    }

    @Test
    void testFeedbackWithDifferentEntityTypes() {
        var result = feedbackManager.recordFeedback(
                "ICU住院患者", "department", "ICU", "重症监护室"
        );
        assertTrue(result.synonymAdded);

        var syns = dict.getSynonyms("department", "ICU");
        assertTrue(syns.contains("重症监护室"), "ICU should have 重症监护室 as synonym from feedback");
    }
}
