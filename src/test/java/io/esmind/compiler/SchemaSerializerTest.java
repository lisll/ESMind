package io.esmind.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SchemaSerializer 单元测试。
 */
class SchemaSerializerTest {

    private SchemaRegistry registry;
    private SchemaSerializer serializer;

    @BeforeEach
    void setUp() {
        registry = new SchemaRegistry("test_index", "data/test-schema-cache.json");

        // Register sample fields
        registry.register(new SchemaField("patient.patient_id",
                Arrays.asList("患者ID", "患者编号"), "patient.patient_id",
                null, "text", "text", "patient.patient_id.keyword", false, false, true));

        registry.register(new SchemaField("binganshouye.admission_time",
                Arrays.asList("入院时间", "就诊时间"), "binganshouye.admission_time",
                null, "date", "date", null, true, false, true));

        registry.register(new SchemaField("shouyezhenduan",
                Collections.singletonList("首页诊断"), "shouyezhenduan",
                null, "nested", "nested", null, false, false, false));

        registry.register(new SchemaField("shouyezhenduan.diagnosis_name",
                Arrays.asList("诊断名称", "诊断"), "shouyezhenduan.diagnosis_name",
                "shouyezhenduan", "text", "text", "shouyezhenduan.diagnosis_name.accurate",
                false, false, true));

        registry.register(new SchemaField("shouyezhenduan.diagnosis_code",
                Arrays.asList("诊断编码"), "shouyezhenduan.diagnosis_code",
                "shouyezhenduan", "keyword", "keyword", null,
                false, false, true));

        serializer = new SchemaSerializer(registry);
    }

    @Test
    void testToCompactString() {
        String result = serializer.toCompactString();
        assertNotNull(result);
        assertTrue(result.contains("test_index"), "Should contain index name");
        assertTrue(result.contains("patient_id"), "Should contain field name");
        assertTrue(result.contains("首页诊断"), "Should contain table name");
    }

    @Test
    void testToDomainString() {
        String result = serializer.toDomainString(new HashSet<>(Arrays.asList("diagnosis")));
        assertNotNull(result);
        assertTrue(result.contains("diagnosis_name"), "Should contain diagnosis field");
        assertTrue(result.contains("诊断"), "Should contain annotation");
    }

    @Test
    void testToDomainStringEmpty() {
        String result = serializer.toDomainString(new HashSet<>(Arrays.asList("surgery")));
        assertNotNull(result);
        // surgery has no matching fields in our test data
        assertTrue(result.contains("surgery"));
    }

    @Test
    void testToTableString() {
        String result = serializer.toTableString("shouyezhenduan");
        assertNotNull(result);
        assertTrue(result.contains("diagnosis_name"), "Should contain nested field");
    }

    @Test
    void testToTableStringUnknownPath() {
        String result = serializer.toTableString("nonexistent_path");
        // No error, just empty-ish output
        assertNotNull(result);
    }

    @Test
    void testEmptyRegistry() {
        SchemaRegistry emptyReg = new SchemaRegistry("empty", "data/empty.json");
        SchemaSerializer ser = new SchemaSerializer(emptyReg);
        String result = ser.toCompactString();
        assertNotNull(result);
        assertTrue(result.contains("empty"));
    }
}
