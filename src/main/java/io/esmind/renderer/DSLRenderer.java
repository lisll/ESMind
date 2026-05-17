package io.esmind.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.esmind.ast.ASTBuilder;
import io.esmind.ast.QueryNode;

import java.util.List;

/**
 * AST → JSON DSL Renderer。
 * 纯代码构造，不涉及 LLM。
 */
public class DSLRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 将 AST QueryContainer 渲染为完整的 ES DSL JSON 字符串。
     */
    public String render(ASTBuilder.QueryContainer container) {
        ObjectNode root = MAPPER.createObjectNode();

        // 1. Size
        root.put("size", container.getSize());

        // 2. Query
        if (container.getQuery() != null) {
            JsonNode queryNode = renderNode(container.getQuery());
            if (queryNode != null) {
                root.set("query", queryNode);
            }
        }

        // 3. Aggregation
        if (container.getAggregation() != null) {
            root.set("aggs", renderAggregation(container.getAggregation()));
        }

        // 4. Sort
        if (container.getSort() != null) {
            root.set("sort", renderSort(container.getSort()));
        } else if (container.getQuery() != null) {
            // 默认不添加排序
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 渲染任意 QueryNode（分发）。
     */
    private JsonNode renderNode(QueryNode node) {
        if (node instanceof QueryNode.BoolNode) {
            return renderBool((QueryNode.BoolNode) node);
        } else if (node instanceof QueryNode.NestedNode) {
            return renderNested((QueryNode.NestedNode) node);
        } else if (node instanceof QueryNode.TermNode) {
            return renderTerm((QueryNode.TermNode) node);
        } else if (node instanceof QueryNode.MatchPhraseNode) {
            return renderMatchPhrase((QueryNode.MatchPhraseNode) node);
        } else if (node instanceof QueryNode.MatchNode) {
            return renderMatch((QueryNode.MatchNode) node);
        } else if (node instanceof QueryNode.RangeNode) {
            return renderRange((QueryNode.RangeNode) node);
        } else if (node instanceof QueryNode.ExistsNode) {
            return renderExists((QueryNode.ExistsNode) node);
        }
        return null;
    }

    private JsonNode renderBool(QueryNode.BoolNode bool) {
        ObjectNode boolNode = MAPPER.createObjectNode();

        if (!bool.getMust().isEmpty()) {
            boolNode.set("must", renderList(bool.getMust()));
        }
        if (!bool.getShould().isEmpty()) {
            boolNode.set("should", renderList(bool.getShould()));
            boolNode.put("minimum_should_match", bool.getMinimumShouldMatch());
        }
        if (!bool.getFilter().isEmpty()) {
            boolNode.set("filter", renderList(bool.getFilter()));
        }
        if (!bool.getMustNot().isEmpty()) {
            boolNode.set("must_not", renderList(bool.getMustNot()));
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.set("bool", boolNode);
        return root;
    }

    private JsonNode renderNested(QueryNode.NestedNode nested) {
        ObjectNode nestedObj = MAPPER.createObjectNode();
        nestedObj.put("path", nested.getPath());
        // inner query
        if (nested.getQuery() != null) {
            nestedObj.set("query", renderNode(nested.getQuery()));
        }
        // inner_hits（仅在 > 0 时添加）
        if (nested.getInnerHitsSize() > 0) {
            nestedObj.putObject("inner_hits").put("size", nested.getInnerHitsSize());
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.set("nested", nestedObj);
        return root;
    }

    private JsonNode renderTerm(QueryNode.TermNode term) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("term").put(term.getField(), term.getValue());
        return root;
    }

    private JsonNode renderMatchPhrase(QueryNode.MatchPhraseNode mp) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("match_phrase").put(mp.getField(), mp.getValue());
        return root;
    }

    private JsonNode renderMatch(QueryNode.MatchNode match) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("match").put(match.getField(), match.getValue());
        return root;
    }

    private JsonNode renderRange(QueryNode.RangeNode range) {
        ObjectNode rangeObj = MAPPER.createObjectNode();
        ObjectNode fieldObj = rangeObj.putObject(range.getField());
        if (range.getGte() != null) fieldObj.put("gte", range.getGte());
        if (range.getLte() != null) fieldObj.put("lte", range.getLte());
        if (range.getGt() != null) fieldObj.put("gt", range.getGt());
        if (range.getLt() != null) fieldObj.put("lt", range.getLt());
        if (range.getFormat() != null) fieldObj.put("format", range.getFormat());

        ObjectNode root = MAPPER.createObjectNode();
        root.set("range", rangeObj);
        return root;
    }

    private JsonNode renderExists(QueryNode.ExistsNode exists) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("exists").put("field", exists.getField());
        return root;
    }

    private JsonNode renderAggregation(QueryNode.AggregationNode agg) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode terms = root.putObject(agg.getName()).putObject("terms");
        terms.put("field", agg.getField());
        terms.put("size", agg.getSize());
        return root;
    }

    private JsonNode renderSort(QueryNode.SortNode sort) {
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode sortField = MAPPER.createObjectNode();
        sortField.putObject(sort.getField()).put("order", sort.getOrder());
        arr.add(sortField);
        return arr;
    }

    private ArrayNode renderList(List<QueryNode> nodes) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (QueryNode node : nodes) {
            JsonNode rendered = renderNode(node);
            if (rendered != null) {
                arr.add(rendered);
            }
        }
        return arr;
    }
}
