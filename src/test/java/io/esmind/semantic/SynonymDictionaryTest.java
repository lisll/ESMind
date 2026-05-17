package io.esmind.semantic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SynonymDictionary 单元测试。
 */
class SynonymDictionaryTest {

    private SynonymDictionary dict;

    @BeforeEach
    void setUp() {
        // Use a clean instance (not the default singleton) with null file path
        dict = new SynonymDictionary(null);
    }

    @Test
    void testGetSynonymsDisease() {
        List<String> syns = dict.getSynonyms("disease", "脑梗死");
        assertNotNull(syns);
        assertFalse(syns.isEmpty(), "Should have synonyms for 脑梗死");
        assertTrue(syns.contains("脑梗"), "Should include 脑梗 as synonym");
        assertTrue(syns.contains("脑血栓"), "Should include 脑血栓 as synonym");
    }

    @Test
    void testGetSynonymsDiabetes() {
        List<String> syns = dict.getSynonyms("disease", "糖尿病");
        assertTrue(syns.contains("DM"), "Should include DM");
        assertTrue(syns.contains("diabetes"), "Should include diabetes");
    }

    @Test
    void testGetSynonymsLab() {
        List<String> syns = dict.getSynonyms("lab_item", "白细胞");
        assertTrue(syns.contains("WBC"), "Should include WBC");
        assertTrue(syns.contains("白细胞计数"), "Should include 白细胞计数");
    }

    @Test
    void testGetSynonymsEmptyForUnknown() {
        List<String> syns = dict.getSynonyms("disease", "罕见疾病名XYZ");
        // Should return the input value itself
        assertEquals(1, syns.size());
        assertEquals("罕见疾病名XYZ", syns.get(0));
    }

    @Test
    void testGetSynonymsWithFuzzyMatch() {
        // "脑梗" partially matches "脑梗死"
        List<String> syns = dict.getSynonyms("disease", "脑梗");
        assertFalse(syns.isEmpty());
    }

    @Test
    void testGetSynonymsInFuzzySynonym() {
        // "cerebral" appears inside synonyms of 脑梗死
        List<String> syns = dict.getSynonyms("disease", "cerebral");
        assertFalse(syns.isEmpty());
    }

    @Test
    void testGetSynonymsUnknownType() {
        List<String> syns = dict.getSynonyms("surgery", "阑尾炎手术");
        assertEquals(1, syns.size());
        assertEquals("阑尾炎手术", syns.get(0));
    }

    @Test
    void testAddSynonymAtRuntime() {
        dict.addSynonym("lab_item", "肌钙蛋白", "TNI");
        List<String> syns = dict.getSynonyms("lab_item", "肌钙蛋白");
        assertTrue(syns.contains("TNI"), "Should contain runtime-added synonym");
    }

    @Test
    void testAddSynonymsBatch() {
        dict.addSynonyms("department", "ICU", List.of("ICU", "重症监护室"));
        List<String> syns = dict.getSynonyms("department", "ICU");
        assertTrue(syns.contains("ICU"));
    }

    @Test
    void testDefaultSingleton() {
        SynonymDictionary defaultDict = SynonymDictionary.getDefault();
        assertNotNull(defaultDict);
        // Default should have built-in synonyms
        List<String> syns = defaultDict.getSynonyms("disease", "高血压");
        assertFalse(syns.isEmpty());

        // Reset after test
        SynonymDictionary.resetDefault();
    }

    @Test
    void testSaveAndLoad() throws Exception {
        dict.addSynonym("disease", "测试疾病", "test-disease");
        // Should not throw
        dict.saveToFile();
        // Clean up
        new java.io.File("data/synonyms.json").delete();
    }
}
