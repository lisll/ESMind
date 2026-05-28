package io.esmind.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SemanticParser {

    private static final Logger log = LoggerFactory.getLogger(SemanticParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiUrl;
    private final String apiKey;
    private final String modelName;
    private final HttpClient httpClient;

    private static final String SYSTEM_PROMPT =
        "你是一个医疗查询解析器。将用户的查询转为结构化JSON。\n\n"
        + "规则：\n"
        + "1. 只输出 JSON，不要解释，只输出顶层对象（不要 subQueries 字段）\n"
        + "2. intent: patient_search 或 patient_count\n"
        + "3. entities 中的 type 只从以下选择：disease, symptom, department, lab_item, patient_id, medicine, surgery, exam_item, report_type, time\n"
        + "4. report_type 表示查询类别（如\"检验报告\"→value=\"lab\", \"手术\"→value=\"surgery\", \"病理报告\"→value=\"pathology\"），不要将类别词放到 lab_item 中\n"
        + "5. 时间作为 entity 输出：type=\"time\", value=天数(数字字符串), unit=\"day\"/\"month\"/\"year\"\n"
        + "6. 检验指标如果有比较值（如 >10），设置 operator (gt/gte/lt/lte/eq) 和 numericValue\n"
        + "7. 重要：禁止使用 subQueries 字段。如果查询包含多个子问题，将它们合并为一个查询，提取最相关的患者、诊断和公共时间约束。\n\n"
        + "示例1: \"最近30天脑梗死患者\"\n"
        + "输出: {\"intent\":\"patient_search\",\"entities\":[{\"type\":\"disease\",\"value\":\"脑梗死\"},{\"type\":\"time\",\"value\":\"30\",\"unit\":\"day\"}]}\n\n"
        + "示例2: \"ICU 白细胞大于10患者\"\n"
        + "输出: {\"intent\":\"patient_search\",\"entities\":[{\"type\":\"department\",\"value\":\"ICU\"},{\"type\":\"lab_item\",\"value\":\"白细胞\",\"operator\":\"gt\",\"numericValue\":\"10\"}]}\n\n"
        + "示例3: \"统计糖尿病患者数量\"\n"
        + "输出: {\"intent\":\"patient_count\",\"entities\":[{\"type\":\"disease\",\"value\":\"糖尿病\"}],\"aggregation\":{\"type\":\"count\"}}\n\n"
        + "示例4: \"查询002499899700这个人的近7天检验报告\"\n"
        + "输出: {\"intent\":\"patient_search\",\"entities\":[{\"type\":\"patient_id\",\"value\":\"002499899700\"},{\"type\":\"report_type\",\"value\":\"lab\"},{\"type\":\"time\",\"value\":\"7\",\"unit\":\"day\"}]}\n\n"
        + "示例5: \"高血压患者近150天有手术且近120天有检验报告\"\n"
        + "输出: {\"intent\":\"patient_search\",\"entities\":[{\"type\":\"disease\",\"value\":\"高血压\"},{\"type\":\"report_type\",\"value\":\"lab\"},{\"type\":\"report_type\",\"value\":\"surgery\"},{\"type\":\"time\",\"value\":\"150\",\"unit\":\"day\"}]}";

    public SemanticParser(String apiUrl, String apiKey, String modelName) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public SemanticIR parse(String nlQuery) throws Exception {
        log.info("SemanticParser.parse: {}", nlQuery);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(nlQuery)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM API error: " + response.statusCode() + " " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        String content = root.get("choices").get(0).get("message").get("content").asText().trim();

        // 保存 raw LLM response
        System.setProperty("esmind.raw_llm_response", content);

        // 处理可能的 markdown 代码块包裹
        if (content.startsWith("```")) {
            int start = content.indexOf('\n') + 1;
            int end = content.lastIndexOf("```");
            content = content.substring(start, end).trim();
        }

        SemanticIR ir = MAPPER.readValue(content, SemanticIR.class);
        log.info("Parsed IR: intent={}, entities={}",
                ir.getIntent(), ir.getEntities().size());

        return ir;
    }

    private String buildRequestBody(String userMsg) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode body = MAPPER.createObjectNode();
            body.put("model", modelName);
            body.put("temperature", 0.1);
            body.put("max_tokens", 500);

            ArrayNode messages = body.putArray("messages");
            com.fasterxml.jackson.databind.node.ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", SYSTEM_PROMPT);

            com.fasterxml.jackson.databind.node.ObjectNode userMsgNode = messages.addObject();
            userMsgNode.put("role", "user");
            userMsgNode.put("content", userMsg);

            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build LLM request", e);
        }
    }
}
