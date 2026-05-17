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
