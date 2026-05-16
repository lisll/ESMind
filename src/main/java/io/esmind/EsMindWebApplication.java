package io.esmind;

import io.esmind.agent.EsTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Spring Boot entry point for ESMind Web Interface.
 * Initialises the {@link HarnessAgent} as a Spring bean and exposes
 * REST endpoints for NL → ES query interactions.
 */
@SpringBootApplication
public class EsMindWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(EsMindWebApplication.class, args);
    }

    // -------------------------------------------------------------------------
    // Property keys (same as EsMindAgent)
    // -------------------------------------------------------------------------
    static final String PROP_BASE_URL = "esmind.model.base-url";
    static final String PROP_API_KEY = "esmind.model.api-key";
    static final String PROP_MODEL = "esmind.model.name";
    static final String PROP_ES_HOST = "esmind.es.host";
    static final String PROP_ES_PORT = "esmind.es.port";
    static final String PROP_ES_SCHEME = "esmind.es.scheme";
    static final String PROP_ES_USER = "esmind.es.username";
    static final String PROP_ES_PASS = "esmind.es.password";
    static final String PROP_WORKSPACE = "esmind.workspace";
    static final String PROP_SERVER_PORT = "server.port";

    static final String ENV_API_KEY = "DASHSCOPE_API_KEY";
    static final String ENV_MODEL = "AGENTSCOPE_MODEL";
    static final String ENV_ES_HOST = "ES_HOST";
    static final String ENV_ES_PORT = "ES_PORT";
    static final String ENV_ES_SCHEME = "ES_SCHEME";
    static final String ENV_WORKSPACE = "AGENTSCOPE_WORKSPACE";

    static final String DEFAULT_MODEL = "qwen-max";
    static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    static final String DEFAULT_WORKSPACE = ".agentscope/workspace";
    static final String DEFAULT_ES_HOST = "localhost";
    static final int DEFAULT_ES_PORT = 9200;
    static final String DEFAULT_ES_SCHEME = "http";
    static final int DEFAULT_SERVER_PORT = 8090;

    // -------------------------------------------------------------------------
    // Spring Configuration — build shared beans
    // -------------------------------------------------------------------------

    @Configuration
    static class AgentConfig {

        @Bean
        @Primary
        Properties esmindProperties() throws IOException {
            return loadProperties();
        }

        @Bean
        EsTool esTool(Properties props) throws IOException {
            String esHost = resolveOverride(props.getProperty(PROP_ES_HOST, DEFAULT_ES_HOST), ENV_ES_HOST);
            int esPort = Integer.parseInt(resolveOverride(
                    props.getProperty(PROP_ES_PORT, String.valueOf(DEFAULT_ES_PORT)), ENV_ES_PORT));
            String esScheme = resolveOverride(props.getProperty(PROP_ES_SCHEME, DEFAULT_ES_SCHEME), ENV_ES_SCHEME);
            String esUsername = props.getProperty(PROP_ES_USER, "");
            String esPassword = props.getProperty(PROP_ES_PASS, "");
            return new EsTool(esHost, esPort, esScheme, esUsername, esPassword);
        }

        @Bean
        Model esmindModel(Properties props) {
            String baseUrl = props.getProperty(PROP_BASE_URL, DEFAULT_BASE_URL);
            String apiKey = resolveSecret(props.getProperty(PROP_API_KEY, ""), ENV_API_KEY);
            String modelName = resolveOverride(props.getProperty(PROP_MODEL, DEFAULT_MODEL), ENV_MODEL);
            return OpenAIChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .stream(false)
                    .build();
        }

        @Bean
        Path workspacePath(Properties props) {
            return Paths.get(resolveOverride(
                    props.getProperty(PROP_WORKSPACE, DEFAULT_WORKSPACE), ENV_WORKSPACE));
        }

        @Bean
        HarnessAgent esmindAgent(Model esmindModel, EsTool esTool, Path workspacePath) {
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(esTool);

            HarnessAgent agent = HarnessAgent.builder()
                    .name("esmind")
                    .sysPrompt("You are an Elasticsearch query expert. Translate natural language"
                            + " questions into Elasticsearch DSL queries, execute them, and present"
                            + " results clearly. Always explore the index mappings first before"
                            + " writing queries. Use the skills in workspace/skills/ for guidance."
                            + " IMPORTANT: Be decisive. Once you have a working query that returns"
                            + " meaningful results, present them and STOP. Do NOT refine or re-execute"
                            + " unless the first query failed. Execute each query once.")
                    .model(esmindModel)
                    .workspace(workspacePath)
                    .toolkit(toolkit)
                    .compaction(CompactionConfig.builder()
                            .triggerMessages(30)
                            .keepMessages(10)
                            .flushBeforeCompact(true)
                            .build())
                    .enableAgentTracingLog(true)
                    .build();

            System.out.println("[web] HarnessAgent 'esmind' built [workspace="
                    + workspacePath.toAbsolutePath() + "]");
            return agent;
        }

        @Bean
        RuntimeContext defaultRuntimeContext() {
            return RuntimeContext.builder()
                    .sessionId("esmind-web")
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Utility methods (shared with EsMindAgent)
    // -------------------------------------------------------------------------

    static Properties loadProperties() throws IOException {
        Properties props = new Properties();
        try (InputStream is = EsMindWebApplication.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
            }
        }
        return props;
    }

    static String resolveOverride(String fileValue, String envName) {
        String envValue = System.getenv(envName);
        return (envValue != null && !envValue.isBlank()) ? envValue : fileValue;
    }

    static String resolveSecret(String fileValue, String... envNames) {
        for (String envName : envNames) {
            String envValue = System.getenv(envName);
            if (envValue != null && !envValue.isBlank()) {
                return envValue;
            }
        }
        return fileValue;
    }
}
