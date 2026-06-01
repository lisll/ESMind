package io.esmind.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;

/**
 * MedicalQueryAgent — AgentScope Agent 服务，封装 HarnessAgent + MedicalQueryTool。
 * <p>
 * Agent 在构造时初始化一次，每个请求使用不同的 session ID 调用。
 * 不持有状态，每个请求独立。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>AgentScope 只负责意图判断 → 调用 query_medical_data 工具</li>
 *   <li>工具内部走 v2 Compiler 管线（不变）</li>
 *   <li>不浪费 token 生成 DSL——工具封装了所有结构化工件</li>
 * </ul>
 */
public class MedicalQueryAgent {

    private static final Logger log = LoggerFactory.getLogger(MedicalQueryAgent.class);

    private static final String SYSTEM_PROMPT =
            "你是一个医疗数据查询助手。你的职责是理解用户的自然语言查询，"
          + "然后调用 query_medical_data 工具来执行查询。\n\n"
          + "规则：\n"
          + "1. 用户可能查询：诊断、检验报告、检查报告、处方、手术、病理、就诊记录等\n"
          + "2. 支持时间范围：近N天、年月日等\n"
          + "3. 支持聚合：按月分布、各科室统计等\n"
          + "4. 直接调用 query_medical_data，不要自己生成 ES DSL\n"
          + "5. 如果用户问的是天气、股票、新闻等非医疗问题，告诉用户你只能回答医疗数据相关查询\n\n"
          + "查询示例：\n"
          + "- \"糖尿病患者\" → 调用 query_medical_data(query=\"糖尿病患者\")\n"
          + "- \"近30天脑梗死患者\" → 调用 query_medical_data(query=\"近30天脑梗死患者\")\n"
          + "- \"门诊就诊记录按月分布\" → 调用 query_medical_data(query=\"门诊就诊记录按月分布\")\n"
          + "- \"患者002499899700的检验报告\" → 调用 query_medical_data(query=\"患者002499899700的检验报告\")";

    private final HarnessAgent agent;

    public MedicalQueryAgent(String apiBaseUrl, String apiKey, String modelName,
                             MedicalQueryTool medicalQueryTool,
                             String toolGroupDescription) {
        log.info("[MedicalQueryAgent] Initializing with model={} @ {}", modelName, apiBaseUrl);

        Model model = OpenAIChatModel.builder()
                .baseUrl(apiBaseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(false)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(medicalQueryTool);
        // 注册 tool group（动态描述）
        toolkit.createToolGroup("medical_tables", toolGroupDescription);
        toolkit.setActiveGroups(java.util.List.of("medical_tables"));

        this.agent = HarnessAgent.builder()
                .name("esmind-agent")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .maxIters(3)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(5).keepMessages(3).build())
                .disableWorkspaceContext()
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableSessionPersistence()
                .disableSubagents()
                .build();

        log.info("[MedicalQueryAgent] Ready");
    }

    public String query(String query) {
        if (query == null || query.isBlank()) {
            return "请输入查询内容。";
        }

        log.info("[MedicalQueryAgent] query: {}", query);

        try {
            String sessionId = "agent-" + UUID.randomUUID().toString().substring(0, 8);
            io.agentscope.core.agent.RuntimeContext ctx =
                    io.agentscope.core.agent.RuntimeContext.builder()
                            .sessionId(sessionId)
                            .build();

            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(query)
                    .build();

            log.info("[MedicalQueryAgent] calling agent (session={})", sessionId);
            Msg result = agent.call(userMsg, ctx).block(Duration.ofSeconds(120));

            if (result == null) {
                log.warn("[MedicalQueryAgent] agent returned null");
                return "查询超时或返回空结果。";
            }

            String text = result.getTextContent();
            log.info("[MedicalQueryAgent] result ({} chars): {}",
                    text != null ? text.length() : 0,
                    text != null ? text.substring(0, Math.min(100, text.length())) : "null");

            return text != null && !text.isBlank() ? text : "未返回结果。";

        } catch (Exception e) {
            log.error("[MedicalQueryAgent] error: {}", e.getMessage(), e);
            return "查询出错：" + e.getMessage();
        }
    }
}
