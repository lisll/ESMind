package io.esmind.compiler;

import io.esmind.semantic.SemanticIR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * QueryPlanner — 查询复杂度评估。
 * <p>
 * 核心职责：判断当前查询能否压成单一 ES DSL。
 * <ul>
 *   <li>SIMPLE: 单一 DSL 可表达 → 直接走 DSLRenderer</li>
 *   <li>COMPLEX: 需要多步 ES 查询 + 结果聚合</li>
 *   <li>TASK: 涉及多步执行、中间状态、条件分支（Phase 3 Workflow）</li>
 * </ul>
 * <p>
 * 当前实现：90%+ 的医疗查询都是 SIMPLE（单一索引、过滤器、聚合）。
 * COMPLEX/TASK 在 Phase 3 Workflow Engine 实现后才真正使用。
 */
public class QueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanner.class);

    public enum PlanType {
        SINGLE,     // 单一 DSL 可表达
        COMPLEX,    // 需要多步 ES 查询
        TASK        // 需要 Workflow Engine
    }

    public static class Plan {
        private final PlanType type;
        private final String reason;
        private final SemanticIR ir;

        public Plan(PlanType type, String reason, SemanticIR ir) {
            this.type = type;
            this.reason = reason;
            this.ir = ir;
        }

        public PlanType getType() { return type; }
        public String getReason() { return reason; }
        public SemanticIR getIr() { return ir; }

        public boolean isSingleDsl() { return type == PlanType.SINGLE; }
        public boolean needsMultiStep() { return type == PlanType.COMPLEX; }
        public boolean needsWorkflow() { return type == PlanType.TASK; }
    }

    private final SchemaRegistry schemaRegistry;
    private final BusinessSemanticRegistry businessRegistry;

    public QueryPlanner() {
        this(null, null);
    }

    public QueryPlanner(SchemaRegistry schemaRegistry,
                        BusinessSemanticRegistry businessRegistry) {
        this.schemaRegistry = schemaRegistry;
        this.businessRegistry = businessRegistry;
    }

    /**
     * 评估查询复杂度。
     */
    public Plan evaluate(SemanticIR ir) {
        if (ir == null) {
            return new Plan(PlanType.SINGLE, "empty IR", null);
        }

        List<SemanticIR.Entity> entities = ir.getEntities();
        if (entities == null || entities.isEmpty()) {
            return new Plan(PlanType.SINGLE, "no entities — match_all", ir);
        }

        // 检查是否有多表 SHOULD（disease 展开为 3 张诊断表）
        // 这是正常的，DSLRenderer 已经支持多表 should 组合 → 仍然 SINGLE
        if (hasDiseaseExpansion(entities)) {
            log.debug("QueryPlanner: disease expansion detected, still SINGLE");
        }

        // 检查是否涉及多个不相关的 Table
        Set<String> tables = extractTables(entities);
        if (tables.size() > 3) {
            log.info("QueryPlanner: {} unique tables, checking for cross-table complexity", tables.size());
        }

        // 检查是否有跨表 AND 条件（不同嵌套路径的组合）
        if (hasCrossNestedAnd(entities)) {
            return new Plan(PlanType.COMPLEX,
                    "cross-nested AND conditions across different tables", ir);
        }

        // 检查是否涉及 Workflow
        if (hasWorkflowIndicators(ir)) {
            return new Plan(PlanType.TASK,
                    "multi-step task requiring workflow engine", ir);
        }

        return new Plan(PlanType.SINGLE, "single DSL query", ir);
    }

    // ========================================================================
    // 内部判断
    // ========================================================================

    /** 判断是否有 disease 展开（3张诊断表的 SHOULD 组合） */
    private boolean hasDiseaseExpansion(List<SemanticIR.Entity> entities) {
        boolean hasShouyezhenduan = false;
        boolean hasMenzhenzhenduan = false;
        boolean hasMenzhenshuju = false;

        for (SemanticIR.Entity e : entities) {
            if ("shouyezhenduan".equals(e.getTable())) hasShouyezhenduan = true;
            if ("menzhenzhenduan".equals(e.getTable())) hasMenzhenzhenduan = true;
            if ("menzhenshuju".equals(e.getTable())) hasMenzhenshuju = true;
        }

        return hasShouyezhenduan && (hasMenzhenzhenduan || hasMenzhenshuju);
    }

    /** 提取所有涉及的业务表 */
    private Set<String> extractTables(List<SemanticIR.Entity> entities) {
        Set<String> tables = new LinkedHashSet<>();
        for (SemanticIR.Entity e : entities) {
            if (e.getTable() != null) {
                tables.add(e.getTable());
            }
        }
        return tables;
    }

    /**
     * 判断是否有跨不同 nested 路径的 AND 条件。
     * 当前 DSLRenderer 可以处理 SHOULD（OR），
     * 但不同 nested 表的 MUST（AND）需要多条 DSL 或 script。
     */
    private boolean hasCrossNestedAnd(List<SemanticIR.Entity> entities) {
        // 收集所有 NESTED context 的表
        Map<String, String> nestedContexts = new HashMap<>();
        for (SemanticIR.Entity e : entities) {
            if ("NESTED".equals(e.getContext()) && e.getContextPath() != null) {
                nestedContexts.put(e.getTable(), e.getContextPath());
            }
        }

        if (nestedContexts.size() <= 1) return false;

        // 如果不同实体有相同的 group（disease 展开），是 SHOULD 不是 AND
        Set<String> groups = new HashSet<>();
        for (SemanticIR.Entity e : entities) {
            if (e.getGroup() != null) {
                groups.add(e.getGroup());
            }
        }

        // 同一个 group 内的多表是 SHOULD（OR）关系，不算跨表 AND
        if (!groups.isEmpty()) {
            int groupedTables = 0;
            for (SemanticIR.Entity e : entities) {
                if (e.getGroup() != null && nestedContexts.containsKey(e.getTable())) {
                    groupedTables++;
                }
            }
            if (groupedTables == nestedContexts.size()) {
                return false; // 所有 nested 表都是同一个 group → SHOULD
            }
        }

        return true;
    }

    /** 判断是否为多步 Workflow 任务 */
    private boolean hasWorkflowIndicators(SemanticIR ir) {
        // === Phase 3: Workflow Engine 检测 ===
        // 当前通过 query text 检测 pivot 模式（"X的患者Y"）
        // 实际检测在 ChatController 中完成，这里只做接口占位
        // 当需要基于 IR 内容做检测时，扩展此方法
        return false;
    }

    /**
     * 基于原始 NL 查询文本检测 pivot/workflow 模式。
     * 在 ChatController 中调用，早于 LLM 解析。
     */
    public boolean isPivotQuery(String nlQuery) {
        if (nlQuery == null || nlQuery.isBlank()) return false;
        // "X的患者Y" — 典型的 pivot 模式
        String[] parts = nlQuery.split("的患者", 2);
        if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
            log.info("QueryPlanner: PIVOT detected: '{}' → '{}'", parts[0].trim(), parts[1].trim());
            return true;
        }
        return false;
    }

    /** 生成可读的 plan 摘要 */
    public String describe(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan: ").append(plan.getType());
        sb.append(" — ").append(plan.getReason());

        if (plan.getIr() != null && plan.getIr().getEntities() != null) {
            sb.append(" (").append(plan.getIr().getEntities().size()).append(" entities)");
        }

        return sb.toString();
    }
}
