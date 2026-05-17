package io.esmind.ast;

import io.esmind.compiler.SchemaField;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.semantic.SemanticIR;
import io.esmind.strategy.StrategySelector;

import java.util.List;

/**
 * AST Builder — 将 SemanticIR + Strategy 构建为 AST。
 *
 * <p>职责：
 * <ol>
 *   <li>遍历 SemanticIR 的 entities</li>
 *   <li>对每个 entity，通过 StrategySelector 获取 AST 节点</li>
 *   <li>处理 time_constraint 生成 RangeNode</li>
 *   <li>组装 BoolNode + NestedNode 树</li>
 *   <li>处理 aggregation</li>
 * </ol>
 */
public class ASTBuilder {

    private final SchemaRegistry schema;
    private final StrategySelector strategySelector;

    public ASTBuilder(SchemaRegistry schema, StrategySelector strategySelector) {
        this.schema = schema;
        this.strategySelector = strategySelector;
    }

    /**
     * 从 SemanticIR 构建完整的查询 AST。
     */
    public QueryContainer build(SemanticIR ir) {
        QueryNode.BoolNode root = new QueryNode.BoolNode();
        int order = 0;

        // 1. 处理每个实体
        List<SemanticIR.Entity> entities = ir.getEntities();
        for (SemanticIR.Entity entity : entities) {
            QueryNode node = strategySelector.buildNode(entity, order++);
            // entity 默认加到 must
            root.addMust(node);
        }

        // 2. 时间约束
        SemanticIR.TimeConstraint tc = ir.getTimeConstraint();
        if (tc != null) {
            QueryNode.RangeNode range = buildTimeRange(tc);
            if (range != null) {
                root.addFilter(range); // 时间范围用 filter（不影响评分）
            }
        }

        // 3. 处理 aggregation
        QueryNode.AggregationNode aggNode = null;
        SemanticIR.Aggregation agg = ir.getAggregation();
        if (agg != null && "count".equals(agg.getType())) {
            aggNode = new QueryNode.AggregationNode();
            aggNode.setName("total");
            aggNode.setField("_index"); // count all
        }

        // 4. 构建最终容器
        QueryContainer container = new QueryContainer();
        container.setQuery(root.hasClauses() ? root : new QueryNode.BoolNode());
        container.setSize(ir.getLimit());
        container.setAggregation(aggNode);

        return container;
    }

    /**
     * 将时间约束转为 RangeNode。
     * "最近30天" → binganshouye.admission_time gte now-30d/d
     */
    private QueryNode.RangeNode buildTimeRange(SemanticIR.TimeConstraint tc) {
        if (tc == null) return null;

        QueryNode.RangeNode range = new QueryNode.RangeNode();

        // 目标字段：binganshouye.admission_time
        SchemaField timeField = schema.getByFieldName("binganshouye.admission_time");
        if (timeField == null) {
            // fallback
            timeField = schema.getByFieldName("patient.ini_time");
        }
        range.setField(timeField != null ? timeField.getEsPath() : "binganshouye.admission_time");

        if ("relative".equals(tc.getType())) {
            // "最近30天" → now-30d/d
            String unit = "d";
            if ("month".equals(tc.getUnit())) unit = "M";
            else if ("year".equals(tc.getUnit())) unit = "y";
            range.setGte("now-" + tc.getValue() + unit + "/d");
        } else if ("absolute".equals(tc.getType())) {
            if (tc.getStartDate() != null) range.setGte(tc.getStartDate());
            if (tc.getEndDate() != null) range.setLte(tc.getEndDate());
        }

        return range;
    }

    /**
     * AST 查询容器。
     * 包含 query、size、sort、aggregation 等顶级元素。
     */
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
