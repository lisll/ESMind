package io.esmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elasticsearch tool that communicates via the low-level REST client.
 *
 * <p>Works with ES 5.x, 6.x, 7.x, and 8.x — version differences are handled
 * at the JSON parsing layer after auto-detecting the cluster version.
 *
 * <p>Three tools are exposed to the agent:
 * <ul>
 *   <li>{@code es_list_indices} — list all indices with doc counts
 *   <li>{@code es_get_mapping} — describe an index's mapping + sample docs
 *   <li>{@code es_execute_query} — run a DSL query and return results
 * </ul>
 */
public class EsTool {

    private static final Logger log = LoggerFactory.getLogger(EsTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Maximum hits returned by es_execute_query (prevents context overflow). */
    private static final int MAX_HITS = 50;

    /** Default sample size for mapping exploration. */
    private static final int SAMPLE_SIZE = 3;

    private final RestClient client;

    /** Cached ES major version, detected on first successful API call. */
    private volatile int esMajorVersion = 0;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param host     ES host (e.g. "localhost")
     * @param port     ES port (e.g. 9200)
     * @param scheme   "http" or "https"
     * @param username optional basic auth username
     * @param password optional basic auth password
     */
    public EsTool(String host, int port, String scheme, String username, String password) {
        HttpHost httpHost = new HttpHost(host, port, scheme);

        RestClient.Builder builder = RestClient.builder(httpHost)
                .setRequestConfigCallback(cb -> cb
                        .setConnectTimeout(5_000)
                        .setSocketTimeout(30_000));

        if (username != null && !username.isBlank()) {
            CredentialsProvider creds = new BasicCredentialsProvider();
            creds.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(cb -> cb.setDefaultCredentialsProvider(creds));
        }

        this.client = builder.build();
    }

    // -------------------------------------------------------------------------
    // Version detection
    // -------------------------------------------------------------------------

    /**
     * Returns the ES cluster's major version number (6, 7, 8, …).
     * Cached after the first successful call.
     */
    int detectMajorVersion() throws IOException {
        if (esMajorVersion > 0) return esMajorVersion;
        Response resp = client.performRequest(new Request("GET", "/"));
        JsonNode root = parseBody(resp);
        String raw = root.get("version").get("number").asText();
        int dot = raw.indexOf('.');
        int major = Integer.parseInt(dot > 0 ? raw.substring(0, dot) : raw);
        esMajorVersion = major;
        log.info("Connected to Elasticsearch {} (detected major version {})", raw, major);
        return major;
    }

    /**
     * Safe version read — returns 7 as fallback when detection fails.
     */
    private int majorVersion() {
        try {
            return detectMajorVersion();
        } catch (Exception e) {
            log.warn("Could not detect ES version, assuming 7.x", e);
            return 7;
        }
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    /** Parse a REST response body into a JsonNode. */
    private static JsonNode parseBody(Response resp) throws IOException {
        try (InputStream is = resp.getEntity().getContent()) {
            return MAPPER.readTree(is);
        }
    }

    /** Parse a string into a JsonNode. */
    private static JsonNode parseJson(String json) throws IOException {
        return MAPPER.readTree(json);
    }

    /** Pretty-print a JsonNode as indented JSON string. */
    private static String toPretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return node.toPrettyString();
        }
    }

    // -------------------------------------------------------------------------
    // Total hits — differs between ES 6.x (number) and 7.x+ (object)
    // -------------------------------------------------------------------------

    private static long parseTotalHits(JsonNode hitsNode) {
        JsonNode total = hitsNode.get("total");
        if (total == null) return 0;
        if (total.isObject()) {
            // ES 7.x+: {"value": N, "relation": "eq"}
            return total.get("value").asLong();
        }
        // ES 6.x: plain number
        return total.asLong();
    }

    // -------------------------------------------------------------------------
    // Mapping extraction — differs between ES 6.x (wrapped by type) and 7.x+
    // -------------------------------------------------------------------------

    /**
     * Given the response from {@code GET /{index}/_mapping}, extract the
     * {@code "properties"} map for the given index name.
     *
     * <p>ES 6.x shape:
     * <pre>{@code
     *   {"index": {"mappings": {"_doc": {"properties": {...}}}}}
     * }</pre>
     *
     * <p>ES 7.x+ shape:
     * <pre>{@code
     *   {"index": {"mappings": {"properties": {...}}}}
     * }</pre>
     */
    static JsonNode extractProperties(JsonNode mappingRoot, String indexName) {
        JsonNode indexNode = mappingRoot.get(indexName);
        if (indexNode == null) return null;

        JsonNode mappings = indexNode.get("mappings");
        if (mappings == null) return null;

        // ES 7.x+: properties live directly under mappings
        JsonNode props = mappings.get("properties");
        if (props != null) return props;

        // ES 6.x: mappings wraps with a type name (e.g. "_doc", "docs")
        Iterator<String> fieldNames = mappings.fieldNames();
        while (fieldNames.hasNext()) {
            String typeName = fieldNames.next();
            JsonNode typeNode = mappings.get(typeName);
            if (typeNode.isObject() && typeNode.has("properties")) {
                return typeNode.get("properties");
            }
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------

    @Tool(
            name = "es_list_indices",
            description = "Lists all accessible Elasticsearch indices with their document" +
                    " counts. Use this first to discover what data is" +
                    " available before writing queries.")
    public String listIndices() {
        try {
            // Trigger version detection (also verifies connectivity)
            majorVersion();

            Request req = new Request("GET", "/_cat/indices?format=json&expand_wildcards=all");
            JsonNode arr = parseBody(client.performRequest(req));

            if (arr.size() == 0) {
                return "No indices found in the Elasticsearch cluster.";
            }

            StringBuilder sb = new StringBuilder("Indices (").append(arr.size()).append("):\n");
            for (JsonNode idx : arr) {
                String name = idx.get("index").asText();
                long docs = idx.has("docs.count") ? idx.get("docs.count").asLong(0) : 0;
                String store = idx.has("store.size") ? idx.get("store.size").asText("?") : "?";
                sb.append("  · **").append(name).append("**  (")
                        .append(docs).append(" docs, ").append(store).append(")\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("es_list_indices failed", e);
            return "Error listing indices: " + e.getMessage();
        }
    }

    @Tool(
            name = "es_get_mapping",
            description = "Returns the mapping (field names, types, nested structures)" +
                    " for one or more Elasticsearch indices, plus sample documents." +
                    " Pass a comma-separated list of index names.")
    public String getMapping(
            @ToolParam(name = "indices",
                    description = "Comma-separated index names, e.g. \"logs-*,orders\"")
            String indices) {
        StringBuilder sb = new StringBuilder();
        for (String idx : indices.split(",")) {
            idx = idx.strip();
            if (idx.isEmpty()) continue;
            sb.append(describeIndex(idx)).append("\n\n---\n\n");
        }
        return sb.toString().strip();
    }

    @Tool(
            name = "es_execute_query",
            description = "Executes a search query against Elasticsearch indices and returns" +
                    " results as a formatted table. The 'query' parameter must be a valid" +
                    " Elasticsearch DSL JSON object (e.g. {\\\"match\\\": {\\\"field\\\": \\\"value\\\"}})." +
                    " Supports match, term, range, bool, aggs, etc.")
    public String executeQuery(
            @ToolParam(name = "indices",
                    description = "Comma-separated target index names, e.g. \"my-index\"")
            String indices,
            @ToolParam(name = "query",
                    description = "Elasticsearch DSL query as a JSON object." +
                            " Example: {\\\"match\\\": {\\\"title\\\": \\\"elasticsearch\\\"}}")
            String query,
            @ToolParam(name = "size", description = "Max results to return (default: 10)",
                    defaultValue = "10")
            int size) {
        try {
            int actualSize = Math.min(size, MAX_HITS);

            // Parse the user's query JSON
            JsonNode queryNode = parseJson(query);

            // Build the full search request body
            // If the JSON is a full search body (has "query", "aggs", etc.), use as-is
            // Otherwise wrap {"query": <user_input>, "size": N}
            String bodyJson;
            if (queryNode.has("query") || queryNode.has("aggs") || queryNode.has("suggest")
                    || queryNode.has("collapse") || queryNode.has("highlight")) {
                // Full search body — override size to respect our cap
                ObjectNode wrapped = (ObjectNode) queryNode;
                wrapped.put("size", actualSize);
                bodyJson = MAPPER.writeValueAsString(wrapped);
            } else {
                // Query fragment — wrap in a full body
                ObjectNode wrapped = MAPPER.createObjectNode();
                wrapped.put("size", actualSize);
                wrapped.set("query", queryNode);
                bodyJson = MAPPER.writeValueAsString(wrapped);
            }

            // Execute
            String joinedIndices = String.join(",", indices.split(","));
            Request req = new Request("POST", "/" + joinedIndices + "/_search");
            req.setJsonEntity(bodyJson);

            JsonNode root = parseBody(client.performRequest(req));
            return formatSearchResponse(root, actualSize);

        } catch (JsonProcessingException e) {
            return "Error: Invalid JSON query. Please provide a valid Elasticsearch DSL JSON.\n"
                    + "Example: {\\\"match\\\": {\\\"field\\\": \\\"value\\\"}}\n"
                    + "Error detail: " + e.getMessage();
        } catch (Exception e) {
            log.warn("es_execute_query failed", e);
            return "Error executing query: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Describe a single index: mapping + sample documents.
     */
    private String describeIndex(String indexName) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Index: ").append(indexName).append("\n\n");

        try {
            // --- Mapping ---
            Request req = new Request("GET", "/" + indexName + "/_mapping");
            JsonNode mappingRoot = parseBody(client.performRequest(req));
            JsonNode props = extractProperties(mappingRoot, indexName);

            sb.append("### Mapping Properties\n");
            if (props != null && props.size() > 0) {
                appendProperties(sb, props, "  ");
            } else {
                sb.append("(dynamic mapping — no explicit property definitions)\n");
            }

            // --- Sample documents ---
            sb.append("\n### Sample Documents (").append(SAMPLE_SIZE).append(" rows)\n");
            ObjectNode sampleBody = MAPPER.createObjectNode();
            sampleBody.put("size", SAMPLE_SIZE);
            sampleBody.set("query", MAPPER.createObjectNode().putObject("match_all"));

            Request sampleReq = new Request("POST", "/" + indexName + "/_search");
            sampleReq.setJsonEntity(MAPPER.writeValueAsString(sampleBody));
            JsonNode sampleResp = parseBody(client.performRequest(sampleReq));
            sb.append(formatSearchResponse(sampleResp, SAMPLE_SIZE));

        } catch (Exception e) {
            sb.append("Error describing index '").append(indexName)
                    .append("': ").append(e.getMessage());
        }
        return sb.toString();
    }

    /**
     * Recursively append mapping properties in a readable format.
     */
    @SuppressWarnings("unchecked")
    private void appendProperties(StringBuilder sb, JsonNode props, String indent) {
        Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            JsonNode fieldDef = entry.getValue();

            String type = fieldDef.has("type") ? fieldDef.get("type").asText() : "object";
            sb.append(indent).append("- **").append(fieldName).append("** (`").append(type).append("`)");

            if (fieldDef.has("fields")) {
                sb.append(" (multi-field)");
            }
            sb.append("\n");

            // Recurse into nested properties
            if (fieldDef.has("properties")) {
                appendProperties(sb, fieldDef.get("properties"), indent + "    ");
            }
        }
    }

    /**
     * Format a search response JSON node into a human-readable string.
     */
    private String formatSearchResponse(JsonNode root, int maxHits) {
        JsonNode hits = root.get("hits");
        if (hits == null) {
            return "(no hits in response)";
        }

        long total = parseTotalHits(hits);
        JsonNode hitArray = hits.get("hits");
        if (hitArray == null || hitArray.size() == 0) {
            return "(no results returned)";
        }

        int showCount = Math.min(hitArray.size(), maxHits);
        StringBuilder sb = new StringBuilder();
        sb.append("Total hits: ").append(total).append("\n");
        sb.append("Showing top ").append(showCount).append(" results:\n\n");

        for (int i = 0; i < showCount; i++) {
            JsonNode hit = hitArray.get(i);
            String id = hit.has("_id") ? hit.get("_id").asText() : "?";
            String index = hit.has("_index") ? hit.get("_index").asText() : "?";
            double score = hit.has("_score") && !hit.get("_score").isNull()
                    ? hit.get("_score").asDouble() : 0.0;

            sb.append("**Hit #").append(i + 1)
                    .append("** | Score: ").append(String.format("%.2f", score))
                    .append(" | Index: ").append(index)
                    .append(" | ID: ").append(id).append("\n");

            JsonNode source = hit.get("_source");
            if (source != null) {
                sb.append("```json\n").append(toPretty(source)).append("\n```\n");
            } else {
                // For field-only responses (no _source)
                JsonNode fields = hit.get("fields");
                if (fields != null) {
                    sb.append("```json\n").append(toPretty(fields)).append("\n```\n");
                } else {
                    sb.append("(no source fields — metadata-only result)\n");
                }
            }
            sb.append("\n");
        }

        if (total > showCount) {
            sb.append("... (").append(total - showCount).append(" more results omitted)\n");
        }

        // Append aggregations if present
        if (root.has("aggregations")) {
            sb.append("\n### Aggregations\n");
            sb.append("```json\n").append(toPretty(root.get("aggregations"))).append("\n```\n");
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Close the underlying HTTP client. */
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("Error closing ES client", e);
        }
    }
}
