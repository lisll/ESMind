package io.esmind.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * AST 节点基类。
 * 所有查询节点继承此类。DSLRenderer 基于节点类型分发渲染。
 */
public abstract class QueryNode {
    private final String nodeType;

    protected QueryNode(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getNodeType() { return nodeType; }

    // ===== BoolNode =====
    public static class BoolNode extends QueryNode {
        private List<QueryNode> must = new ArrayList<>();
        private List<QueryNode> should = new ArrayList<>();
        private List<QueryNode> filter = new ArrayList<>();
        private List<QueryNode> mustNot = new ArrayList<>();
        private int minimumShouldMatch = 1;

        public BoolNode() { super("bool"); }

        public void addMust(QueryNode node) { must.add(node); }
        public void addShould(QueryNode node) { should.add(node); }
        public void addFilter(QueryNode node) { filter.add(node); }
        public void addMustNot(QueryNode node) { mustNot.add(node); }

        public List<QueryNode> getMust() { return must; }
        public List<QueryNode> getShould() { return should; }
        public List<QueryNode> getFilter() { return filter; }
        public List<QueryNode> getMustNot() { return mustNot; }
        public int getMinimumShouldMatch() { return minimumShouldMatch; }
        public void setMinimumShouldMatch(int v) { this.minimumShouldMatch = v; }

        public boolean hasClauses() {
            return !must.isEmpty() || !should.isEmpty() || !filter.isEmpty() || !mustNot.isEmpty();
        }
    }

    // ===== NestedNode =====
    public static class NestedNode extends QueryNode {
        private String path;            // "shouyezhenduan"
        private QueryNode query;        // 内部查询
        private int innerHitsSize = 0;  // 0=不返回 inner_hits，>0返回

        public NestedNode() { super("nested"); }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public QueryNode getQuery() { return query; }
        public void setQuery(QueryNode query) { this.query = query; }

        public int getInnerHitsSize() { return innerHitsSize; }
        public void setInnerHitsSize(int size) { this.innerHitsSize = size; }
    }

    // ===== TermNode =====
    public static class TermNode extends QueryNode {
        private String field;
        private String value;

        public TermNode() { super("term"); }

        public TermNode(String field, String value) {
            this();
            this.field = field;
            this.value = value;
        }

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    // ===== MatchPhraseNode =====
    public static class MatchPhraseNode extends QueryNode {
        private String field;
        private String value;

        public MatchPhraseNode() { super("match_phrase"); }

        public MatchPhraseNode(String field, String value) {
            this();
            this.field = field;
            this.value = value;
        }

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    // ===== MatchNode =====
    public static class MatchNode extends QueryNode {
        private String field;
        private String value;

        public MatchNode() { super("match"); }

        public MatchNode(String field, String value) {
            this();
            this.field = field;
            this.value = value;
        }

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    // ===== RangeNode =====
    public static class RangeNode extends QueryNode {
        private String field;
        private String gte;
        private String lte;
        private String gt;
        private String lt;
        private String format;

        public RangeNode() { super("range"); }

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }

        public String getGte() { return gte; }
        public void setGte(String gte) { this.gte = gte; }

        public String getLte() { return lte; }
        public void setLte(String lte) { this.lte = lte; }

        public String getGt() { return gt; }
        public void setGt(String gt) { this.gt = gt; }

        public String getLt() { return lt; }
        public void setLt(String lt) { this.lt = lt; }

        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
    }

    // ===== AggregationNode =====
    public static class AggregationNode extends QueryNode {
        private String name;
        private String field;
        private int size = 10;

        public AggregationNode() { super("aggregation"); }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }

        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
    }

    // ===== ExistsNode =====
    public static class ExistsNode extends QueryNode {
        private String field;

        public ExistsNode() { super("exists"); }

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
    }

    // ===== SortNode =====
    public static class SortNode {
        private String field;
        private String order = "desc";

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getOrder() { return order; }
        public void setOrder(String order) { this.order = order; }
    }
}
