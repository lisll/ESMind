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
 *   <li>nested 表用 QueryNode.NestedNode 包裹，非 nested 表直接挂 root</li>
 *   <li>同 group 的不同表 Entity 用 SHOULD 组合（如跨表诊断搜索）</li>
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

        // 1. 分离 Entity：有 group 的（需 SHOULD 组合）vs 无 group 的（普通 table 分组）
        Map<String, List<SemanticIR.Entity>> groupedEntities = new LinkedHashMap<>();  // group → entities
        List<SemanticIR.Entity> ungroupedEntities = new ArrayList<>();
        List<SemanticIR.Entity> rootLevel = new ArrayList<>();

        for (SemanticIR.Entity entity : ir.getEntities()) {
            if (entity.getClauseType() == null) {
                log.warn("Entity with unresolved clauseType: type={}, value={}, skipping", entity.getType(), entity.getValue());
                continue;
            }
            String group = entity.getGroup();
            if (group != null && !group.isEmpty()) {
                groupedEntities.computeIfAbsent(group, k -> new ArrayList<>()).add(entity);
            } else if (entity.getTable() != null && !entity.getTable().isEmpty()) {
                ungroupedEntities.add(entity);
            } else {
                rootLevel.add(entity);
            }
        }

        // 2. 同 group Entity → 构建 SHOULD 子句
        for (List<SemanticIR.Entity> group : groupedEntities.values()) {
            QueryNode queryNode = buildShouldClause(group);
            if (queryNode != null) {
                root.addMust(queryNode);
            }
        }

        // 3. 无 group Entity → 按 table 分组，用 MUST
        Map<String, List<SemanticIR.Entity>> groupByTable = new LinkedHashMap<>();
        for (SemanticIR.Entity entity : ungroupedEntities) {
            groupByTable.computeIfAbsent(entity.getTable(), k -> new ArrayList<>()).add(entity);
        }

        for (Map.Entry<String, List<SemanticIR.Entity>> entry : groupByTable.entrySet()) {
            List<SemanticIR.Entity> ents = entry.getValue();
            QueryNode node = buildTableGroup(ents);
            if (node != null) {
                root.addMust(node);
            }
        }

        // 4. 顶级 Entity（table = null）→ 直接挂在 root 下
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

        // 5. Aggregation
        QueryNode.AggregationNode aggNode = null;
        SemanticIR.Aggregation agg = ir.getAggregation();
        if (agg != null) {
            aggNode = new QueryNode.AggregationNode();
            String aggName = agg.getName() != null ? agg.getName() : "agg_" + agg.getType();
            aggNode.setName(aggName);

            switch (agg.getType()) {
                case "count":
                    aggNode.setType("terms");
                    aggNode.setField("_index");
                    break;
                case "date_histogram":
                    aggNode.setType("date_histogram");
                    if (agg.getField() != null) aggNode.setField(agg.getField());
                    if (agg.getInterval() != null) aggNode.setInterval(agg.getInterval());
                    if (agg.getFormat() != null) aggNode.setFormat(agg.getFormat());
                    aggNode.setSize(agg.getSize() > 0 ? agg.getSize() : 100);
                    break;
                case "terms":
                    aggNode.setType("terms");
                    if (agg.getField() != null) aggNode.setField(agg.getField());
                    if (agg.getSize() > 0) aggNode.setSize(agg.getSize());
                    break;
                default:
                    log.warn("Unknown aggregation type: {}", agg.getType());
                    aggNode = null;
                    break;
            }
        }

        // 6. 构建容器
        QueryContainer container = new QueryContainer();
        container.setQuery(root.hasClauses() ? root : new QueryNode.BoolNode());

        // 聚合查询：date_histogram 设 size=0 只取聚合结果
        if (aggNode != null && "date_histogram".equals(aggNode.getType())) {
            container.setSize(0);
        } else {
            container.setSize(ir.getLimit());
        }
        container.setAggregation(aggNode);
        return container;
    }

    /**
     * 同 group 的 Entity 用 SHOULD 组合（跨表搜索）。
     * 每个 Entity 独立构建 nested/非 nested 子句，然后 SHOULD 到一起。
     */
    private QueryNode buildShouldClause(List<SemanticIR.Entity> group) {
        QueryNode.BoolNode should = new QueryNode.BoolNode();
        should.setMinimumShouldMatch(1);

        // 再按 table 分组，同一 table 的放一个 NestedNode 里
        Map<String, List<SemanticIR.Entity>> byTable = new LinkedHashMap<>();
        for (SemanticIR.Entity e : group) {
            byTable.computeIfAbsent(e.getTable(), k -> new ArrayList<>()).add(e);
        }

        for (Map.Entry<String, List<SemanticIR.Entity>> entry : byTable.entrySet()) {
            String table = entry.getKey();
            List<SemanticIR.Entity> ents = entry.getValue();

            if (isNested(ents)) {
                // nested 表 → 包 NestedNode
                QueryNode.BoolNode inner = new QueryNode.BoolNode();
                for (SemanticIR.Entity e : ents) {
                    QueryNode clause = buildClause(e);
                    if (clause != null) inner.addMust(clause);
                }
                QueryNode.NestedNode nested = new QueryNode.NestedNode();
                nested.setPath(ents.get(0).getContextPath());
                nested.setQuery(inner);
                should.addShould(nested);
            } else {
                // 非 nested 表 → 直接挂
                for (SemanticIR.Entity e : ents) {
                    QueryNode clause = buildClause(e);
                    if (clause != null) should.addShould(clause);
                }
            }
        }

        return should.hasClauses() ? should : null;
    }

    /** 同一 table 的一组 Entity → 包 NestedNode（如需要） */
    private QueryNode buildTableGroup(List<SemanticIR.Entity> group) {
        if (isNested(group)) {
            QueryNode.BoolNode inner = new QueryNode.BoolNode();
            QueryNode.NestedNode nested = new QueryNode.NestedNode();
            nested.setPath(group.get(0).getContextPath());
            nested.setQuery(inner);

            boolean hasRangeFilter = false;
            for (SemanticIR.Entity entity : group) {
                if ("range".equals(entity.getClauseType())) {
                    QueryNode.RangeNode range = new QueryNode.RangeNode();
                    range.setField(entity.getField());
                    applyTimeValues(range, entity);
                    if (range.getGte() != null || range.getLte() != null
                            || range.getGt() != null || range.getLt() != null) {
                        inner.addFilter(range);
                        hasRangeFilter = true;
                    }
                } else {
                    QueryNode node = buildClause(entity);
                    if (node != null) inner.addMust(node);
                }
            }
            if (hasRangeFilter && nested.getInnerHitsSize() == 0) {
                nested.setInnerHitsSize(10);
            }
            return nested;
        } else {
            // 非 nested 表 → 直接挂 root
            for (SemanticIR.Entity entity : group) {
                QueryNode node = buildClause(entity);
                if (node != null) return node;
            }
            return null;
        }
    }

    // ========================================================================
    // 子句构建 — 纯机械 switch
    // ========================================================================

    /** 检查一组 Entity 是否属于 nested 上下文（使用第一个 Entity 的 context 字段） */
    private boolean isNested(List<SemanticIR.Entity> entities) {
        if (entities.isEmpty()) return false;
        return "NESTED".equals(entities.get(0).getContext());
    }

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
                QueryNode.RangeNode range = new QueryNode.RangeNode();
                range.setField(entity.getField());
                applyTimeValues(range, entity);
                return range;

            default:
                log.warn("Unknown clauseType '{}', treating as match_phrase", clauseType);
                return new QueryNode.MatchPhraseNode("total_src", entity.getValue());
        }
    }

    // ========================================================================
    // 时间值处理 — 纯值转换，无决策
    // ========================================================================

    private void applyTimeValues(QueryNode.RangeNode range, SemanticIR.Entity entity) {
        String value = entity.getValue();
        if (value == null) return;

        String timeType = entity.getTimeType();

        if ("ABSOLUTE".equals(timeType)) {
            // 绝对时间：value = yyyy-MM-dd 或 yyyy-MM 或 yyyy
            String gte = value;
            String lte = value;

            // 年份 "2025" → "2025-01-01" ~ "2025-12-31"
            if (value.matches("\\d{4}")) {
                gte = value + "-01-01";
                lte = value + "-12-31";
            }
            // 年月 "2026-03" → "2026-03-01" ~ 月末
            else if (value.matches("\\d{4}-\\d{2}")) {
                gte = value + "-01";
                // 计算当月最后一天
                try {
                    java.time.YearMonth ym = java.time.YearMonth.parse(value);
                    lte = value + "-" + ym.lengthOfMonth();
                } catch (Exception e) {
                    lte = value + "-28"; // fallback
                }
            }

            range.setFormat("yyyy-MM-dd");
            range.setGte(gte);
            range.setLte(lte);
            return;
        }

        // RELATIVE 时间（默认）
        try {
            int num = Integer.parseInt(value);
            String u = "d";
            if ("month".equals(entity.getUnit())) u = "M";
            else if ("year".equals(entity.getUnit())) u = "y";
            range.setGte("now-" + num + u + "/d");
            return;
        } catch (NumberFormatException ignored) {
        }

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
