package io.esmind.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * ES REST 客户端（精简版）。
 * 仅供 compiler 模块使用。
 */
public class EsRestClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EsRestClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private volatile int esMajorVersion = 0;

    public EsRestClient(String host, int port, String scheme) {
        this.client = RestClient.builder(new HttpHost(host, port, scheme))
                .setRequestConfigCallback(cb -> cb.setConnectTimeout(5000).setSocketTimeout(60000))
                .build();
    }

    public String getMapping(String indexName) throws Exception {
        Request req = new Request("GET", "/" + indexName + "/_mapping");
        return bodyToString(client.performRequest(req));
    }

    public String search(String indexName, String bodyJson) throws Exception {
        Request req = new Request("POST", "/" + indexName + "/_search");
        req.setJsonEntity(bodyJson);
        return bodyToString(client.performRequest(req));
    }

    public String count(String indexName, String bodyJson) throws Exception {
        Request req = new Request("POST", "/" + indexName + "/_count");
        req.setJsonEntity(bodyJson);
        return bodyToString(client.performRequest(req));
    }

    public long simpleCount(String indexName) throws Exception {
        Request req = new Request("GET", "/" + indexName + "/_count");
        JsonNode root = parseBody(client.performRequest(req));
        return root.get("count").asLong();
    }

    /**
     * 批量查询多个表的文档数（使用 _msearch）。
     * tableTypes: Map<表名, 类型> — "nested" 或 "object"
     * 返回 Map<表名, 文档数>
     */
    public Map<String, Long> batchCount(String indexName, Map<String, String> tableTypes) throws Exception {
        if (tableTypes == null || tableTypes.isEmpty()) return Collections.emptyMap();

        List<String> names = new ArrayList<>(tableTypes.keySet());
        StringBuilder body = new StringBuilder();
        for (String table : names) {
            String type = tableTypes.get(table);
            body.append("{}\n");
            if ("nested".equals(type)) {
                body.append("{\"size\":0,\"query\":{\"nested\":{\"path\":\"")
                        .append(table).append("\",\"query\":{\"match_all\":{}}}}}}\n");
            } else {
                body.append("{\"size\":0,\"query\":{\"exists\":{\"field\":\"")
                        .append(table).append("\"}}}}\n");
            }
        }

        Request req = new Request("POST", "/" + indexName + "/_msearch");
        req.setJsonEntity(body.toString());
        req.addParameter("filter_path", "responses.status,responses.hits.total");

        JsonNode root = parseBody(client.performRequest(req));
        Map<String, Long> result = new LinkedHashMap<>();
        JsonNode responses = root.get("responses");
        if (responses != null && responses.isArray()) {
            int i = 0;
            for (JsonNode resp : responses) {
                String path = names.get(i);
                int status = resp.get("status").asInt();
                if (status == 200 && resp.has("hits")) {
                    JsonNode total = resp.get("hits").get("total");
                    // ES 6.x: total is number; ES 7.x+: total is {value:N, relation:"eq"}
                    long count = total.isObject() ? total.get("value").asLong() : total.asLong();
                    result.put(path, count);
                } else {
                    result.put(path, -1L);
                }
                i++;
            }
        }
        return result;
    }

    /**
     * 批量查询多个 nested/object 表的文档数（使用 _msearch）。
     * 兼容旧接口，自动按类型分组。
     */
    public Map<String, Long> batchCount(String indexName, Collection<String> nestedPaths) throws Exception {
        Map<String, String> types = new LinkedHashMap<>();
        for (String p : nestedPaths) types.put(p, "nested");
        return batchCount(indexName, types);
    }

    private static JsonNode parseBody(Response resp) throws Exception {
        try (InputStream is = resp.getEntity().getContent()) {
            return MAPPER.readTree(is);
        }
    }

    private static String bodyToString(Response resp) throws Exception {
        try (InputStream is = resp.getEntity().getContent()) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        try { client.close(); } catch (Exception e) { log.warn("close: {}", e.getMessage()); }
    }
}
