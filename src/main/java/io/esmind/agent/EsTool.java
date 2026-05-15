package io.esmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Iterator;
import java.util.Map;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.GetMappingsRequest;
import org.elasticsearch.client.indices.GetMappingsResponse;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom tool that exposes Elasticsearch operations to the agent.
 *
 * <p>Provides three tools the agent can call:
 *
 * <ul>
 *   <li>{@code es_list_indices} — list all accessible indices
 *   <li>{@code es_get_mapping} — describe an index's mapping (fields, types)
 *   <li>{@code es_execute_query} — run a DSL query and return formatted results
 * </ul>
 *
 * <p>This class demonstrates how to wire a domain-specific tool into a HarnessAgent.
 * Register it via the agent's Toolkit before build().
 */
public class EsTool {

    private static final Logger log = LoggerFactory.getLogger(EsTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Maximum hits returned by es_execute_query to prevent context overflow. */
    private static final int MAX_HITS = 50;

    /** Default number of hits for sampling. */
    private static final int SAMPLE_SIZE = 3;

    private final RestHighLevelClient client;

    /**
     * @param host     ES host (e.g. "localhost")
     * @param port     ES port (e.g. 9200)
     * @param scheme   "http" or "https"
     * @param username optional basic auth username
     * @param password optional basic auth password
     */
    public EsTool(String host, int port, String scheme, String username, String password) {
        HttpHost httpHost = new HttpHost(host, port, scheme);

        if (username != null && !username.isBlank()) {
            CredentialsProvider creds = new BasicCredentialsProvider();
            creds.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));
            this.client = new RestHighLevelClient(
                    RestClient.builder(httpHost)
                            .setHttpClientConfigCallback(cb ->
                                    cb.setDefaultCredentialsProvider(creds)));
        } else {
            this.client = new RestHighLevelClient(RestClient.builder(httpHost));
        }
    }

    // -------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------

    @Tool(
            name = "es_list_indices",
            description = "Lists all accessible Elasticsearch indices with their document" +
                    " counts and storage sizes. Use this first to discover what data is" +
                    " available before writing queries.")
    public String listIndices() {
        try {
            GetIndexRequest request = new GetIndexRequest();
            request.indicesOptions(org.elasticsearch.action.support.IndicesOptions
                    .fromOptions(false, false, true, false));
            String[] indices = client.indices().get(request, RequestOptions.DEFAULT)
                    .getMappings().keySet().toArray(new String[0]);

            if (indices.length == 0) {
                return "No indices found in the Elasticsearch cluster.";
            }

            StringBuilder sb = new StringBuilder("Indices (").append(indices.length).append("):\n");
            for (String idx : indices) {
                // Get doc count via a match_all query
                SearchRequest sr = new SearchRequest(idx);
                SearchSourceBuilder ssb = new SearchSourceBuilder()
                        .query(QueryBuilders.matchAllQuery())
                        .size(0);
                sr.source(ssb);
                SearchResponse resp = client.search(sr, RequestOptions.DEFAULT);
                long count = resp.getHits().getTotalHits().value;
                sb.append("  · ").append(idx).append("  (").append(count).append(" docs)\n");
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
                    " Elasticsearch DSL JSON object (e.g. {\"match\": {\"field\": \"value\"}})." +
                    " Supports match, term, range, bool, aggs, etc.")
    public String executeQuery(
            @ToolParam(name = "indices",
                    description = "Comma-separated target index names, e.g. \"my-index\"")
            String indices,
            @ToolParam(name = "query",
                    description = "Elasticsearch DSL query as a JSON object." +
                            " Example: {\"match\": {\"title\": \"elasticsearch\"}}")
            String query,
            @ToolParam(name = "size", description = "Max results to return (default: 10)",
                    defaultValue = "10")
            int size) {
        try {
            int actualSize = Math.min(size, MAX_HITS);
            SearchSourceBuilder ssb = new SearchSourceBuilder()
                    .size(actualSize);

            // Parse the query JSON
            JsonNode queryNode = MAPPER.readTree(query);
            ssb.query(QueryBuilders.wrapperQuery(query));

            SearchRequest sr = new SearchRequest(indices.split(","));
            sr.source(ssb);

            SearchResponse response = client.search(sr, RequestOptions.DEFAULT);
            return formatSearchResponse(response, actualSize);
        } catch (JsonProcessingException e) {
            return "Error: Invalid JSON query. Please provide a valid Elasticsearch DSL JSON.\n"
                    + "Example: {\"match\": {\"field\": \"value\"}}\n"
                    + "Error detail: " + e.getMessage();
        } catch (Exception e) {
            log.warn("es_execute_query failed", e);
            return "Error executing query: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String describeIndex(String indexName) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Index: ").append(indexName).append("\n\n");

        try {
            // Get mapping
            GetMappingsRequest request = new GetMappingsRequest()
                    .indices(indexName);
            GetMappingsResponse response = client.indices()
                    .getMapping(request, RequestOptions.DEFAULT);

            MappingMetadata mm = response.mappings().get(indexName);
            if (mm == null) {
                return "Index '" + indexName + "' not found or has no mapping.";
            }

            Map<String, Object> sourceMap = mm.getSourceAsMap();
            sb.append("### Mapping Properties\n");
            if (sourceMap != null && sourceMap.containsKey("properties")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (Map<String, Object>) sourceMap.get("properties");
                appendProperties(sb, props, "  ");
            } else {
                sb.append("(dynamic mapping — no explicit property definitions)\n");
            }

            // Sample documents
            sb.append("\n### Sample Documents (").append(SAMPLE_SIZE).append(" rows)\n");
            SearchRequest sr = new SearchRequest(indexName);
            SearchSourceBuilder ssb = new SearchSourceBuilder()
                    .query(QueryBuilders.matchAllQuery())
                    .size(SAMPLE_SIZE);
            sr.source(ssb);
            SearchResponse resp = client.search(sr, RequestOptions.DEFAULT);
            sb.append(formatSearchResponse(resp, SAMPLE_SIZE));

        } catch (Exception e) {
            sb.append("Error describing index '").append(indexName)
                    .append("': ").append(e.getMessage());
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendProperties(StringBuilder sb, Map<String, Object> props, String indent) {
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            String fieldName = entry.getKey();
            if (entry.getValue() instanceof Map) {
                Map<String, Object> fieldDef = (Map<String, Object>) entry.getValue();
                String type = String.valueOf(fieldDef.getOrDefault("type", "object"));
                sb.append(indent).append("- **").append(fieldName).append("** (`").append(type).append("`)");

                if (fieldDef.containsKey("properties")) {
                    sb.append("\n");
                    appendProperties(sb, (Map<String, Object>) fieldDef.get("properties"), indent + "    ");
                } else {
                    if (fieldDef.containsKey("fields")) {
                        sb.append(" (multi-field)");
                    }
                    sb.append("\n");
                }
            }
        }
    }

    private String formatSearchResponse(SearchResponse response, int maxHits) {
        org.elasticsearch.search.SearchHits hits = response.getHits();
        long total = hits.getTotalHits().value;
        SearchHit[] hitArray = hits.getHits();

        if (hitArray.length == 0) {
            return "(no results returned)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Total hits: ").append(total).append("\n");
        sb.append("Showing top ").append(Math.min(hitArray.length, maxHits)).append(" results:\n\n");

        // Collect all field names from the first hit for table header
        for (int i = 0; i < Math.min(hitArray.length, maxHits); i++) {
            SearchHit hit = hitArray[i];
            sb.append("**Hit #").append(i + 1)
                    .append("** | Score: ").append(String.format("%.2f", hit.getScore()))
                    .append(" | Index: ").append(hit.getIndex())
                    .append(" | ID: ").append(hit.getId()).append("\n");

            Map<String, Object> source = hit.getSourceAsMap();
            if (source != null) {
                try {
                    String json = MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(source);
                    sb.append("```json\n").append(json).append("\n```\n");
                } catch (JsonProcessingException e) {
                    sb.append(source).append("\n");
                }
            } else {
                sb.append("(no source fields — metadata-only result)\n");
            }
            sb.append("\n");
        }

        if (total > maxHits) {
            sb.append("... (").append(total - maxHits).append(" more results omitted)\n");
        }
        return sb.toString();
    }

    /** Close the underlying ES client. */
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("Error closing ES client", e);
        }
    }
}
