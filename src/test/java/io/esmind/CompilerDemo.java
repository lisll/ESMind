package io.esmind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.esmind.agent.EsMindCompiler;
import io.esmind.ast.ASTBuilder;
import io.esmind.ast.QueryNode;
import io.esmind.compiler.EsRestClient;
import io.esmind.compiler.SchemaField;
import io.esmind.compiler.SchemaLoader;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.renderer.DSLRenderer;
import io.esmind.semantic.SemanticIR;
import io.esmind.semantic.SemanticParser;
import io.esmind.strategy.StrategySelector;
import io.esmind.validator.QueryValidator;

/**
 * ESMind 编译器集成测试。
 * 测试完整的 NL → IR → AST → DSL → ES 执行链路。
 */
public class CompilerDemo {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String INDEX = "ccm_history_2025_test_clinical_inhistory_0820154042";

    public static void main(String[] args) throws Exception {
        System.out.println("=== ESMind Compiler Demo ===");
        System.out.println("Index: " + INDEX);

        // 1. 初始化 Schema
        System.out.println("\n[1] Initializing SchemaRegistry...");
        EsRestClient esClient = new EsRestClient("192.168.8.59", 9230, "http");
        SchemaRegistry schema = new SchemaLoader(INDEX, esClient).load();
        System.out.println("  Fields: " + schema.size());
        System.out.println("  Nested tables: " + schema.getNestedPaths());

        // 2. 打印 schema field 示例
        System.out.println("\n[Schema sample fields with biz names]:");
        for (SchemaField f : schema.getAllFields()) {
            if (f.getBizNames() != null && !f.getBizNames().isEmpty()
                    && !f.getBizNames().get(0).contains(".")) {
                System.out.println("  " + f.getFieldName() + " → " + f.getBizNames().get(0));
            }
        }
        // only print first 30
        // (we'll skip this in real run)

        // 3. 初始化各组件
        System.out.println("\n[2] Initializing components...");
        StrategySelector strategySelector = new StrategySelector(schema);
        ASTBuilder astBuilder = new ASTBuilder(schema, strategySelector);
        DSLRenderer renderer = new DSLRenderer();
        QueryValidator validator = new QueryValidator(schema);

        // 4. 测试 DSL 生成（不依赖 LLM）
        System.out.println("\n[3] Testing DSL generation (without LLM)...");

        // 测试1: 脑梗死患者
        System.out.println("\n--- Test 1: 脑梗死患者 ---");
        testDSL(schema, astBuilder, renderer, validator,
                "disease", "脑梗死", null, null);

        // 测试2: 最近30天脑梗死
        System.out.println("\n--- Test 2: 最近30天脑梗死患者 ---");
        testDSLWithTime(schema, astBuilder, renderer, validator,
                "disease", "脑梗死", 30, "day");

        // 测试3: ICU患者
        System.out.println("\n--- Test 3: ICU患者 ---");
        testDSL(schema, astBuilder, renderer, validator,
                "department", "ICU", null, null);

        // 测试4: 糖尿病统计
        System.out.println("\n--- Test 4: 统计糖尿病患者数量 ---");
        testDSL(schema, astBuilder, renderer, validator,
                "disease", "糖尿病", null, null);

        // 5. ES 执行测试（如果 ES 可达）
        System.out.println("\n[4] ES execution test...");
        try {
            long docCount = esClient.simpleCount(INDEX);
            System.out.println("  ES connected! Document count: " + docCount);

            // 执行一个实际查询
            System.out.println("\n  Executing: 脑梗死患者...");
            SemanticIR ir = new SemanticIR();
            ir.setIntent("patient_search");
            ir.addEntity(new SemanticIR.Entity("disease", "脑梗死"));

            ASTBuilder.QueryContainer container = astBuilder.build(ir);
            String dsl = renderer.render(container);
            String result = esClient.search(INDEX, dsl);

            JsonNode root = MAPPER.readTree(result);
            long total = root.get("hits").get("total").asLong();
            System.out.println("  Total hits: " + total);

        } catch (Exception e) {
            System.out.println("  ES test skipped (ES not reachable?): " + e.getMessage());
        }

        System.out.println("\n=== Demo Complete ===");
    }

    static void testDSL(SchemaRegistry schema, ASTBuilder builder,
                        DSLRenderer renderer, QueryValidator validator,
                        String entityType, String entityValue,
                        String operator, String numVal) {
        SemanticIR ir = new SemanticIR();
        ir.setIntent("patient_search");

        SemanticIR.Entity ent = new SemanticIR.Entity(entityType, entityValue);
        ent.setOperator(operator);
        ent.setNumericValue(numVal);
        ir.addEntity(ent);

        ASTBuilder.QueryContainer container = builder.build(ir);
        String dsl = renderer.render(container);
        System.out.println("  DSL:\n" + dsl);

        if (container.getQuery() != null) {
            QueryValidator.ValidationResult vr = validator.validate(container.getQuery());
            System.out.println("  Validation: " + vr.summary());
        }
        System.out.println();
    }

    static void testDSLWithTime(SchemaRegistry schema, ASTBuilder builder,
                                DSLRenderer renderer, QueryValidator validator,
                                String entityType, String entityValue,
                                int timeValue, String timeUnit) {
        SemanticIR ir = new SemanticIR();
        ir.setIntent("patient_search");
        ir.addEntity(new SemanticIR.Entity(entityType, entityValue));
        ir.setTimeConstraint(new SemanticIR.TimeConstraint("relative", timeValue, timeUnit));

        ASTBuilder.QueryContainer container = builder.build(ir);
        String dsl = renderer.render(container);
        System.out.println("  DSL:\n" + dsl);

        if (container.getQuery() != null) {
            QueryValidator.ValidationResult vr = validator.validate(container.getQuery());
            System.out.println("  Validation: " + vr.summary());
        }
        System.out.println();
    }
}
