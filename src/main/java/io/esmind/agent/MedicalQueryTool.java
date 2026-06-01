package io.esmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.esmind.ast.ASTBuilder;
import io.esmind.compiler.EsRestClient;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.renderer.DSLRenderer;
import io.esmind.renderer.ResultTransformer;
import io.esmind.semantic.SemanticIR;
import io.esmind.semantic.SemanticParser;
import io.esmind.template.TemplateEngine;
import io.esmind.validator.QueryValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MedicalQueryTool — 单一 AgentScope @Tool，封装完整的医疗数据查询管线。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>一个 Tool 替代 300+ 细粒度工具</li>
 *   <li>接收自然语言查询，内部调用 v2 Compiler 管线</li>
 *   <li>管线不变：SemanticParser → ASTBuilder → DSLRenderer → EsRestClient → ResultTransformer</li>
 *   <li>AgentScope 只负责意图判断和工具选择，不参与 DSL 生成</li>
 * </ul>
 * <p>
 * 管线流程：
 * <pre>
 *   NL Query → SemanticParser (LLM intent) → TemplateEngine (resolution)
 *           → ASTBuilder → DSLRenderer → EsRestClient (ES 6.5.4) → ResultTransformer
 * </pre>
 */
public class MedicalQueryTool {

    private static final Logger log = LoggerFactory.getLogger(MedicalQueryTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EsMindCompiler compiler;

    public MedicalQueryTool(SemanticParser semanticParser,
                            TemplateEngine templateEngine,
                            DSLRenderer dslRenderer,
                            QueryValidator queryValidator,
                            EsRestClient esRestClient,
                            ResultTransformer resultTransformer,
                            String indexName,
                            SchemaRegistry schemaRegistry) {
        this.compiler = new EsMindCompiler(
                semanticParser, templateEngine,
                dslRenderer, queryValidator,
                esRestClient, resultTransformer,
                indexName, schemaRegistry
        );
    }

    /**
     * 直接接收已构建好的 EsMindCompiler（简化构造）。
     */
    public MedicalQueryTool(EsMindCompiler compiler) {
        this.compiler = compiler;
    }

    // ========================================================================
    // Tool
    // ========================================================================

    @Tool(
            name = "query_medical_data",
            description = "查询医疗数据。输入自然语言描述，自动解析为 Elasticsearch 查询并返回结果。" +
                    "支持：按诊断查询患者、查看检验报告、查询就诊记录、统计分布等。" +
                    "支持时间范围过滤（如\"近30天\"、\"2025年\"）。" +
                    "支持聚合统计（如\"按月分布\"、\"各科室统计\"）。" +
                    "注意：不支持天气、股票、新闻等非医疗查询。"
    )
    public String queryMedicalData(
            @ToolParam(name = "query",
                    description = "用户的自然语言查询，如：\"糖尿病患者\"、" +
                            "\"近30天脑梗死患者\"、\"门诊就诊记录按月分布\"、" +
                            "\"患者002499899700的检验报告\"")
            String query
    ) {
        if (query == null || query.isBlank()) {
            return "请提供查询内容。";
        }

        log.info("[MedicalQueryTool] query: {}", query);

        try {
            EsMindCompiler.QueryResponse response = compiler.compile(query);

            StringBuilder sb = new StringBuilder();
            if (response.getAnswer() != null) {
                sb.append(response.getAnswer());
            }

            if (response.getMarkdownTable() != null) {
                sb.append("\n\n").append(response.getMarkdownTable());
            }

            String result = sb.toString();
            if (result.isBlank()) {
                if (response.getError() != null) {
                    return "查询执行失败：" + response.getError();
                }
                return "未找到相关数据。";
            }

            log.info("[MedicalQueryTool] result ({} chars): {}",
                    result.length(), result.substring(0, Math.min(100, result.length())));

            return result;

        } catch (Exception e) {
            log.error("[MedicalQueryTool] error: {}", e.getMessage(), e);
            return "查询出错：" + e.getMessage();
        }
    }

    /**
     * 返回内部的 EsMindCompiler 实例（用于获取 DSL/debug 信息）。
     */
    public EsMindCompiler getCompiler() {
        return compiler;
    }

    /**
     * 获取编译器的 SemanticParser（用于调试）。
     */
    public SemanticParser getSemanticParser() {
        // 从 compiler 中取出 parser
        // 注意：compiler 内部持有 parser，但未对外暴露
        // 这里仅做调试用
        return null;
    }
}
