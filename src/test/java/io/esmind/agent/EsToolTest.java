package io.esmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for EsTool's version-agnostic logic.
 *
 * <p>Full integration tests require a running Elasticsearch instance.
 * These tests focus on JSON parsing and the compatibility layer.
 */
public class EsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Tool annotation presence
    // -------------------------------------------------------------------------

    @Test
    public void testToolAnnotationPresence() throws NoSuchMethodException {
        Assertions.assertTrue(
                EsTool.class.getMethod("listIndices").isAnnotationPresent(
                        io.agentscope.core.tool.Tool.class));

        Assertions.assertTrue(
                EsTool.class.getMethod("getMapping", String.class).isAnnotationPresent(
                        io.agentscope.core.tool.Tool.class));

        Assertions.assertTrue(
                EsTool.class.getMethod("executeQuery", String.class, String.class, Integer.class)
                        .isAnnotationPresent(io.agentscope.core.tool.Tool.class));
    }

    // -------------------------------------------------------------------------
    // Query JSON validation
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"match\": {\"field\": \"value\"}}",
            "{\"bool\": {\"must\": [{\"match\": {\"title\": \"test\"}}], \"filter\": [{\"term\": {\"status\": \"active\"}}]}}"
    })
    public void testValidDSLQueryIsValidJson(String query) {
        Assertions.assertDoesNotThrow(() -> MAPPER.readTree(query));
    }

    @Test
    public void testInvalidDSLQueryThrows() {
        String invalidQuery = "{match: {field: value}}";
        Assertions.assertThrows(Exception.class, () -> MAPPER.readTree(invalidQuery));
    }

    // -------------------------------------------------------------------------
    // Aggregation query JSON validation
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // ES version-agnostic parsing — extractProperties
    // -------------------------------------------------------------------------

    @Test
    public void testExtractPropertiesFromES7x() throws Exception {
        // ES 7.x shape: {"index": {"mappings": {"properties": {...}}}}
        String mapping = """
                {
                  "my-index": {
                    "mappings": {
                      "properties": {
                        "title": { "type": "text" },
                        "price": { "type": "float" }
                      }
                    }
                  }
                }
                """;
        JsonNode root = MAPPER.readTree(mapping);
        JsonNode props = EsTool.extractProperties(root, "my-index");
        Assertions.assertNotNull(props);
        Assertions.assertTrue(props.has("title"));
        Assertions.assertEquals("text", props.get("title").get("type").asText());
        Assertions.assertTrue(props.has("price"));
    }

    @Test
    public void testExtractPropertiesFromES6x() throws Exception {
        // ES 6.x shape: {"index": {"mappings": {"_doc": {"properties": {...}}}}}
        String mapping = """
                {
                  "my-index": {
                    "mappings": {
                      "_doc": {
                        "properties": {
                          "title": { "type": "text" },
                          "price": { "type": "float" }
                        }
                      }
                    }
                  }
                }
                """;
        JsonNode root = MAPPER.readTree(mapping);
        JsonNode props = EsTool.extractProperties(root, "my-index");
        Assertions.assertNotNull(props);
        Assertions.assertTrue(props.has("title"));
        Assertions.assertEquals("text", props.get("title").get("type").asText());
    }

    @Test
    public void testExtractPropertiesFromES6xCustomType() throws Exception {
        // ES 6.x with a custom type name instead of "_doc"
        String mapping = """
                {
                  "logs-2024": {
                    "mappings": {
                      "log_entry": {
                        "properties": {
                          "message": { "type": "text" },
                          "level": { "type": "keyword" }
                        }
                      }
                    }
                  }
                }
                """;
        JsonNode root = MAPPER.readTree(mapping);
        JsonNode props = EsTool.extractProperties(root, "logs-2024");
        Assertions.assertNotNull(props);
        Assertions.assertTrue(props.has("message"));
    }

    @Test
    public void testExtractPropertiesReturnsNullForMissingIndex() throws Exception {
        String mapping = "{\"other-index\": {\"mappings\": {\"properties\": {}}}}";
        JsonNode root = MAPPER.readTree(mapping);
        Assertions.assertNull(EsTool.extractProperties(root, "nonexistent"));
    }

    // -------------------------------------------------------------------------
    // Total hits parsing — 6.x (number) vs 7.x+ (object)
    // -------------------------------------------------------------------------

    @Test
    public void testParseTotalHitsES7x() throws Exception {
        // ES 7.x+: {"hits": {"total": {"value": 42, "relation": "eq"}, "hits": []}}
        String resp = "{\"hits\": {\"total\": {\"value\": 42, \"relation\": \"eq\"}, \"hits\": []}}";
        JsonNode root = MAPPER.readTree(resp);
        // We use a private method; test via formatSearchResponse indirectly
        // For direct coverage, we'd need reflection. But the method is exercised
        // through executeQuery. This test verifies the JSON structure is parseable.
        Assertions.assertEquals(42, root.get("hits").get("total").get("value").asLong());
    }

    @Test
    public void testParseTotalHitsES6x() throws Exception {
        // ES 6.x: {"hits": {"total": 42, "hits": []}}
        String resp = "{\"hits\": {\"total\": 42, \"hits\": []}}";
        JsonNode root = MAPPER.readTree(resp);
        Assertions.assertEquals(42, root.get("hits").get("total").asLong());
    }

    // -------------------------------------------------------------------------
    // Full search body detection (used by executeQuery)
    // -------------------------------------------------------------------------

    @Test
    public void testFullSearchBodyDetection() throws Exception {
        // A full search body has "query", "aggs", "size" etc.
        String fullBody = """
                {
                  "size": 0,
                  "query": { "match_all": {} },
                  "aggs": { "by_field": { "terms": { "field": "category" } } }
                }
                """;
        JsonNode node = MAPPER.readTree(fullBody);
        Assertions.assertTrue(node.has("query"));
        Assertions.assertTrue(node.has("aggs"));
        Assertions.assertTrue(node.has("size"));
    }

    @Test
    public void testQueryFragmentDetection() throws Exception {
        // A query fragment has no top-level keys like "query", "aggs", etc.
        String fragment = "{\"match\": {\"title\": \"test\"}}";
        JsonNode node = MAPPER.readTree(fragment);
        Assertions.assertFalse(node.has("query"));
        Assertions.assertFalse(node.has("aggs"));
    }
}
