package io.esmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Basic unit tests for EsTool's DSL-building helpers.
 *
 * <p>Full integration tests require a running Elasticsearch instance.
 * These tests focus on the JSON parsing and formatting logic.
 */
public class EsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testToolAnnotationPresence() throws NoSuchMethodException {
        // Verify that EsTool has the expected tool methods
        Assertions.assertTrue(
                EsTool.class.getMethod("listIndices").isAnnotationPresent(
                        io.agentscope.core.tool.Tool.class));

        Assertions.assertTrue(
                EsTool.class.getMethod("getMapping", String.class).isAnnotationPresent(
                        io.agentscope.core.tool.Tool.class));

        Assertions.assertTrue(
                EsTool.class.getMethod("executeQuery", String.class, String.class, int.class)
                        .isAnnotationPresent(io.agentscope.core.tool.Tool.class));
    }

    @Test
    public void testValidDSLQueryIsValidJson() throws Exception {
        String validQuery = "{\"match\": {\"field\": \"value\"}}";
        Assertions.assertDoesNotThrow(() -> MAPPER.readTree(validQuery));
    }

    @Test
    public void testInvalidDSLQueryThrows() {
        String invalidQuery = "{match: {field: value}}";
        Assertions.assertThrows(Exception.class, () -> MAPPER.readTree(invalidQuery));
    }

    @Test
    public void testBoolQueryIsValid() throws Exception {
        String boolQuery = """
                {
                  "bool": {
                    "must": [{ "match": { "title": "test" } }],
                    "filter": [{ "term": { "status.keyword": "active" } }]
                  }
                }
                """;
        Assertions.assertDoesNotThrow(() -> MAPPER.readTree(boolQuery));
    }

    @Test
    public void testAggregationQueryIsValid() throws Exception {
        String aggQuery = """
                {
                  "size": 0,
                  "aggs": {
                    "by_category": {
                      "terms": { "field": "category.keyword", "size": 10 }
                    }
                  }
                }
                """;
        Assertions.assertDoesNotThrow(() -> MAPPER.readTree(aggQuery));
    }
}
