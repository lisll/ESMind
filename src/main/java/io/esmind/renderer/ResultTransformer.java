package io.esmind.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.esmind.ast.ASTBuilder;
import io.esmind.ast.QueryNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * ES 搜索结果转换器。
 *
 * <p>职责：将 ES 原始 JSON 响应转换为结构化的可读结果。
 * 设计来源：LangChain Elastic 的 _hits_to_docs_scores + doc_builder 双层映射模式。
 *
 * <p>两层架构：
 * <ol>
 *   <li><b>通用层</b> — hits → DocScore（自动提取 _source、_id、_score、inner_hits）</li>
 *   <li><b>自定义层</b> — 可注入 DocBuilder 自定义字段映射</li>
 * </ol>
 */
public class ResultTransformer {

    private static final Logger log = LoggerFactory.getLogger(ResultTransformer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认构建器：提取 _source 中所有字段 */
    private DocBuilder defaultDocBuilder = DefaultDocBuilder.INSTANCE;

    /** 自定义字段构建器映射（按索引名） */
    private Map<String, DocBuilder> docBuildersByIndex = new HashMap<>();

    public ResultTransformer() {}

    public ResultTransformer(DocBuilder defaultDocBuilder) {
        this.defaultDocBuilder = defaultDocBuilder;
    }

    /**
     * 转换 ES 搜索结果。
     *
     * @param esResult ES 原始 JSON 响应字符串
     * @param container 查询容器（用于提取 inner_hits 配置等）
     * @return 格式化结果
     */
    public TransformResult transform(String esResult, ASTBuilder.QueryContainer container) {
        try {
            JsonNode root = MAPPER.readTree(esResult);
            JsonNode hits = root.get("hits");
            if (hits == null) {
                return new TransformResult(0, Collections.emptyList(), "ES 返回了空结果。");
            }

            long total = parseTotalHits(hits);
            if (total == 0) {
                return new TransformResult(0, Collections.emptyList(), "未查询到符合条件的患者。");
            }

            JsonNode hitArray = hits.get("hits");
            if (hitArray == null || hitArray.size() == 0) {
                return new TransformResult(total, Collections.emptyList(),
                        "共查询到 " + total + " 条结果，但无法获取详细数据。");
            }

            List<DocScore> docs = new ArrayList<>();
            for (JsonNode hit : hitArray) {
                DocScore doc = extractDoc(hit, container);
                docs.add(doc);
            }

            // 构建可读摘要
            String summary = buildSummary(total, docs.size(), container);

            // 构建 Markdown 表格
            String markdownTable = buildMarkdownTable(docs, container);

            return new TransformResult(total, docs, summary, markdownTable);

        } catch (Exception e) {
            log.error("Failed to transform ES result", e);
            return new TransformResult(0, Collections.emptyList(), "结果解析出错: " + e.getMessage());
        }
    }

    /**
     * 从单个 hit 提取字段。
     */
    private DocScore extractDoc(JsonNode hit, ASTBuilder.QueryContainer container) {
        String id = hit.has("_id") ? hit.get("_id").asText() : "";
        String index = hit.has("_index") ? hit.get("_index").asText() : "";
        double score = hit.has("_score") && !hit.get("_score").isNull()
                ? hit.get("_score").asDouble() : 0.0;

        // 获取文档构建器（按索引选择，或使用默认）
        DocBuilder builder = docBuildersByIndex.getOrDefault(index, defaultDocBuilder);

        // 提取 _source
        Map<String, Object> source = new LinkedHashMap<>();
        JsonNode sourceNode = hit.get("_source");
        if (sourceNode != null) {
            source = MAPPER.convertValue(sourceNode, LinkedHashMap.class);
        }

        // 提取 inner_hits（nested 表数据）
        Map<String, List<Map<String, Object>>> innerHits = new LinkedHashMap<>();
        JsonNode innerHitsNode = hit.get("inner_hits");
        if (innerHitsNode != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = innerHitsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String ihName = entry.getKey();
                JsonNode ihData = entry.getValue();
                JsonNode ihHits = ihData.get("hits");
                if (ihHits != null) {
                    JsonNode ihHitArray = ihHits.get("hits");
                    if (ihHitArray != null) {
                        List<Map<String, Object>> ihDocs = new ArrayList<>();
                        for (JsonNode ihHit : ihHitArray) {
                            ihDocs.add(extractInnerHit(ihHit));
                        }
                        innerHits.put(ihName, ihDocs);
                    }
                }
            }
        }

        // 用自定义 DocBuilder 映射字段
        Map<String, Object> docFields = builder.build(source, innerHits, hit);

        return new DocScore(id, index, score, source, innerHits, docFields);
    }

    /**
     * 提取 inner_hit 中的字段。
     */
    private Map<String, Object> extractInnerHit(JsonNode ihHit) {
        Map<String, Object> result = new LinkedHashMap<>();

        // inner_hits 中数据在 _source 或 fields 中
        JsonNode source = ihHit.get("_source");
        if (source != null) {
            result.putAll(MAPPER.convertValue(source, LinkedHashMap.class));
        }
        JsonNode fields = ihHit.get("fields");
        if (fields != null) {
            Iterator<Map.Entry<String, JsonNode>> iter = fields.fields();
            while (iter.hasNext()) {
                Map.Entry<String, JsonNode> entry = iter.next();
                JsonNode value = entry.getValue();
                if (value.isArray() && value.size() > 0) {
                    result.put(entry.getKey(), value.get(0).asText());
                } else {
                    result.put(entry.getKey(), value.asText());
                }
            }
        }
        return result;
    }

    /**
     * 构建结果摘要。
     */
    private String buildSummary(long total, int shown, ASTBuilder.QueryContainer container) {
        StringBuilder sb = new StringBuilder();
        sb.append("共查询到 **").append(total).append("** 条结果");
        if (total > shown) {
            sb.append("，显示前 ").append(shown).append(" 条");
        }
        sb.append("。");

        // 如果有聚合结果
        if (container != null && container.getAggregation() != null) {
            sb.append("（统计查询）");
        }

        return sb.toString();
    }

    /**
     * 构建 Markdown 表格。
     */
    private String buildMarkdownTable(List<DocScore> docs, ASTBuilder.QueryContainer container) {
        if (docs.isEmpty()) return "";

        // 从第一条文档的 docFields 推断表头
        DocScore first = docs.get(0);
        Set<String> headerKeys = first.getDocFields().keySet();

        if (headerKeys.isEmpty()) {
            // 无自定义字段，显示 _source 中的字段
            headerKeys = first.getSource().keySet();
        }

        if (headerKeys.isEmpty() && !first.getInnerHits().isEmpty()) {
            // 只有 inner_hits 数据
            return buildInnerHitsTable(docs);
        }

        if (headerKeys.isEmpty()) return "";

        StringBuilder table = new StringBuilder();

        // 表头
        List<String> headers = new ArrayList<>(headerKeys);
        table.append("| ").append(headers.stream().collect(Collectors.joining(" | "))).append(" |\n");
        table.append("| ").append(headers.stream().map(h -> "---").collect(Collectors.joining(" | "))).append(" |\n");

        // 数据行（最多 20 行）
        int limit = Math.min(docs.size(), 20);
        for (int i = 0; i < limit; i++) {
            DocScore doc = docs.get(i);
            Map<String, Object> fields = doc.getDocFields().isEmpty()
                    ? doc.getSource() : doc.getDocFields();
            table.append("| ");
            for (String h : headers) {
                Object val = fields.getOrDefault(h, "");
                table.append(formatCell(val)).append(" | ");
            }
            table.append("\n");
        }

        return table.toString();
    }

    /**
     * 当只有 inner_hits 数据时，按 nested 表显示。
     */
    private String buildInnerHitsTable(List<DocScore> docs) {
        StringBuilder sb = new StringBuilder();

        for (DocScore doc : docs) {
            sb.append("**ID: `").append(doc.getId()).append("`**");
            if (doc.getScore() > 0) {
                sb.append(" (score: ").append(String.format("%.2f", doc.getScore())).append(")");
            }
            sb.append("\n\n");

            for (Map.Entry<String, List<Map<String, Object>>> ih : doc.getInnerHits().entrySet()) {
                String tableName = ih.getKey();
                List<Map<String, Object>> rows = ih.getValue();

                if (rows.isEmpty()) continue;

                sb.append("**").append(tableName).append("** (").append(rows.size()).append(" rows)\n\n");

                // 从第一个元素推断列
                Set<String> columns = rows.get(0).keySet();
                List<String> colList = new ArrayList<>(columns);

                sb.append("| ").append(colList.stream().collect(Collectors.joining(" | "))).append(" |\n");
                sb.append("| ").append(colList.stream().map(c -> "---").collect(Collectors.joining(" | "))).append(" |\n");

                for (Map<String, Object> row : rows) {
                    sb.append("| ");
                    for (String c : colList) {
                        sb.append(formatCell(row.getOrDefault(c, ""))).append(" | ");
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String formatCell(Object val) {
        if (val == null) return "";
        String s = val.toString();
        if (s.length() > 100) s = s.substring(0, 97) + "...";
        // 转义 Markdown 表格中的竖线
        return s.replace("|", "\\|");
    }

    // ===== Total hits 解析（兼容 ES 6.x 和 7.x+）=====

    private long parseTotalHits(JsonNode hitsNode) {
        JsonNode total = hitsNode.get("total");
        if (total == null) return 0;
        if (total.isObject()) {
            return total.get("value").asLong(); // ES 7.x+
        }
        return total.asLong(); // ES 6.x
    }

    // ===== DocBuilder 接口 =====

    /**
     * 文档构建器接口 — 将原始 ES hit 映射为可读字段。
     * 相当于 LangChain Elastic 的 doc_builder / document_mapper。
     */
    @FunctionalInterface
    public interface DocBuilder {
        Map<String, Object> build(Map<String, Object> source,
                                   Map<String, List<Map<String, Object>>> innerHits,
                                   JsonNode rawHit);
    }

    /** 默认构建器：_source 中的字段直接展开 */
    public static class DefaultDocBuilder implements DocBuilder {
        public static final DefaultDocBuilder INSTANCE = new DefaultDocBuilder();

        @Override
        public Map<String, Object> build(Map<String, Object> source,
                                           Map<String, List<Map<String, Object>>> innerHits,
                                           JsonNode rawHit) {
            Map<String, Object> result = new LinkedHashMap<>(source);

            // 如果有 inner_hits，将第一个 nested 表的前 3 行合并到顶层
            if (!innerHits.isEmpty()) {
                for (Map.Entry<String, List<Map<String, Object>>> entry : innerHits.entrySet()) {
                    List<Map<String, Object>> rows = entry.getValue();
                    if (!rows.isEmpty()) {
                        Map<String, Object> firstRow = rows.get(0);
                        for (Map.Entry<String, Object> f : firstRow.entrySet()) {
                            result.put(entry.getKey() + "." + f.getKey(), f.getValue());
                        }
                    }
                }
            }

            return result;
        }
    }

    // ===== 数据类 =====

    /** 单条文档的解析结果 */
    public static class DocScore {
        private final String id;
        private final String index;
        private final double score;
        private final Map<String, Object> source;
        private final Map<String, List<Map<String, Object>>> innerHits;
        private final Map<String, Object> docFields;

        public DocScore(String id, String index, double score,
                        Map<String, Object> source,
                        Map<String, List<Map<String, Object>>> innerHits,
                        Map<String, Object> docFields) {
            this.id = id;
            this.index = index;
            this.score = score;
            this.source = source;
            this.innerHits = innerHits;
            this.docFields = docFields;
        }

        public String getId() { return id; }
        public String getIndex() { return index; }
        public double getScore() { return score; }
        public Map<String, Object> getSource() { return source; }
        public Map<String, List<Map<String, Object>>> getInnerHits() { return innerHits; }
        public Map<String, Object> getDocFields() { return docFields; }
    }

    /** 完整转换结果 */
    public static class TransformResult {
        private final long totalHits;
        private final List<DocScore> docs;
        private final String summary;
        private final String markdownTable;

        public TransformResult(long totalHits, List<DocScore> docs, String summary) {
            this(totalHits, docs, summary, "");
        }

        public TransformResult(long totalHits, List<DocScore> docs, String summary, String markdownTable) {
            this.totalHits = totalHits;
            this.docs = docs;
            this.summary = summary;
            this.markdownTable = markdownTable;
        }

        public long getTotalHits() { return totalHits; }
        public List<DocScore> getDocs() { return docs; }
        public String getSummary() { return summary; }
        public String getMarkdownTable() { return markdownTable; }
        public boolean isEmpty() { return docs.isEmpty(); }
    }
}
