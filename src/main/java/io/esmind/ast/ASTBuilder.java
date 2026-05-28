package io.esmind.ast;

import io.esmind.semantic.SemanticIR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AST Builder — 将已解析的 SemanticIR 转换为 AST。
 * <p>
 * 纯机械转换，无业务逻辑：
 * <ol>
 *   <li>按 table 字段对 Entity 分组（null → 顶级）</li>
 *   <li>每组创建一个 QueryNode.BoolNode，range 类型进 filter，其余进 must</li>
 *   <li>table 非 null 时用 QueryNode.NestedNode 包裹</li>
 *   <li>处理 aggregation</li>
 * </ol>
 */
public class ASTBuilder {

    private static final Logger log = LoggerFactory.getLogger(ASTBuilder.class);

    public ASTBuilder() {
        // 无状态，无依赖
    }

    /**
     * 从已解析的 SemanticIR 构建查询 AST。
     */
    public QueryContainer build(SemanticIR ir) {
        QueryNode.BoolNode root = new QueryNode.BoolNode();

        // 1. 按 table 分组
        Map<String, List<SemanticIR.Entity>> groupByTable = new LinkedHashMap<>();
        List<SemanticIR.Entity> rootLevel = new ArrayList<>();

        for (SemanticIR.Entity entity : ir.getEntities()) {
            if (entity.getClauseType() == null) {
                log.warn("Entity with unresolved clauseType: type={}, value={}, skipping", entity.getType(), entity.getValue());
                continue;
            }
            String table = entity.getTable();
            if (table != null && !table.isEmpty()) {
                groupByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(entity);
            } else {
                rootLevel.add(entity);
            }
        }

        // 2. 顶级 Entity（table = null）→ 直接挂在 root 下
        for (SemanticIR.Entity entity : rootLevel) {
            QueryNode node = buildClause(entity);
            if (node != null) {
                if ("range".equals(entity.getClauseType())) {
                    root.addFilter(node);
                } else {
                    root.addMust(node);
                }
            }
        }

        // 3. 按表分组的 Entity → 每个分组创建一个 QueryNode.NestedNode
        for (Map.Entry<String, List<SemanticIR.Entity>> entry : groupByTable.entrySet()) {
            String table = entry.getKey();
            List<SemanticIR.Entity> group = entry.getValue();

            QueryNode.BoolNode inner = new QueryNode.BoolNode();
            QueryNode.NestedNode nested = new QueryNode.NestedNode();
            nested.setPath(table);
            nested.setQuery(inner);

            boolean hasRangeFilter = false;

            for (SemanticIR.Entity entity : group) {
                if ("range".equals(entity.getClauseType())) {
                    // 时间 range → 注入 nested 内部的 filter
                    QueryNode.RangeNode range = new QueryNode.RangeNode();
                    range.setField(entity.getField());
                    applyTimeValues(range, entity.getValue(), entity.getUnit());
                    if (range.getGte() != null || range.getLte() != null
                            || range.getGt() != null || range.getLt() != null) {
                        inner.addFilter(range);
                        hasRangeFilter = true;
                    }
                } else {
                    // 非 range → 构建并加入 must
                    QueryNode node = buildClause(entity);
                    if (node != null) {
                        inner.addMust(node);
                    }
                }
            }

            // range filter + exists 组合时打开 inner_hits
            if (hasRangeFilter && nested.getInnerHitsSize() == 0) {
                nested.setInnerHitsSize(10);
            }

            root.addMust(nested);
        }

        // 4. Aggregation
        QueryNode.AggregationNode aggNode = null;
        SemanticIR.Aggregation agg = ir.getAggregation();
        if (agg != null && "count".equals(agg.getType())) {
            aggNode = new QueryNode.AggregationNode();
            aggNode.setName("total");
            aggNode.setField("_index");
        }

        // 5. 构建容器
        QueryContainer container = new QueryContainer();
        container.setQuery(root.hasClauses() ? root : new QueryNode.BoolNode());
        container.setSize(ir.getLimit());
        container.setAggregation(aggNode);
        return container;
    }

    // ========================================================================
    // 子句构建 — 纯机械 switch
    // ========================================================================

    private QueryNode buildClause(SemanticIR.Entity entity) {
        String clauseType = entity.getClauseType();
        if (clauseType == null) return null;

        switch (clauseType) {
            case "term":
                return new QueryNode.TermNode(entity.getField(), entity.getValue());

            case "match_phrase":
                return new QueryNode.MatchPhraseNode(entity.getField(), entity.getValue());

            case "exists":
                QueryNode.ExistsNode exists = new QueryNode.ExistsNode();
                exists.setField(entity.getField());
                return exists;

            case "range":
                // 时间 range（顶级情况，table=null）
                QueryNode.RangeNode range = new QueryNode.RangeNode();
                range.setField(entity.getField());
                applyTimeValues(range, entity.getValue(), entity.getUnit());
                return range;

            default:
                log.warn("Unknown clauseType '{}', treating as match_phrase", clauseType);
                return new QueryNode.MatchPhraseNode("total_src", entity.getValue());
        }
    }

    // ========================================================================
    // 时间值处理 — 纯值转换，无决策
    // ========================================================================

    /**
     * 将 time entity 的 value+unit 转换为 ES range 的 gte/lte。
     */
    private void applyTimeValues(QueryNode.RangeNode range, String value, String unit) {
        if (value == null) return;

        // 尝试解析为数字（相对时间）
        try {
            int num = Integer.parseInt(value);
            String u = "d";
            if ("month".equals(unit)) u = "M";
            else if ("year".equals(unit)) u = "y";
            range.setGte("now-" + num + u + "/d");
            return;
        } catch (NumberFormatException ignored) {
            // 不是数字，可能是绝对时间
        }

        // 绝对时间格式
        range.setGte(value);
    }

    // ========================================================================
    // QueryContainer
    // ========================================================================

    public static class QueryContainer {
        private QueryNode query;
        private int size = 20;
        private QueryNode.AggregationNode aggregation;
        private QueryNode.SortNode sort;

        public QueryNode getQuery() { return query; }
        public void setQuery(QueryNode q) { this.query = q; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public QueryNode.AggregationNode getAggregation() { return aggregation; }
        public void setAggregation(QueryNode.AggregationNode agg) { this.aggregation = agg; }
        public QueryNode.SortNode getSort() { return sort; }
        public void setSort(QueryNode.SortNode sort) { this.sort = sort; }
    }
}
