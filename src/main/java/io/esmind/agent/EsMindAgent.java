package io.esmind.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;

/**
 * ESMind — Natural Language to Elasticsearch Query Agent.
 *
 * <p>Main entry point that builds a {@link HarnessAgent} with:
 *
 * <ul>
 *   <li>Workspace-driven persona from {@code AGENTS.md}
 *   <li>Session persistence (same sessionId resumes previous state)
 *   <li>Conversation compaction with memory flush
 *   <li>Custom {@link EsTool} for Elasticsearch interaction
 *   <li>Skills auto-loaded from workspace/skills/
 *   <li>Subagent orchestration from workspace/subagents/
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <p>Edit {@code src/main/resources/application.properties} (or the
 * {@code application.properties} on the classpath at runtime) to set model
 * and ES connection parameters. Environment variables override file values.
 *
 * <h2>Run</h2>
 *
 * <pre>
 * # Interactive mode
 * mvn compile exec:java -Dexec.mainClass="io.esmind.agent.EsMindAgent"
 *
 * # Single query
 * mvn compile exec:java -Dexec.mainClass="io.esmind.agent.EsMindAgent" \
 *   -Dexec.args="上个月销量最高的产品是什么？"
 * </pre>
 */
public class EsMindAgent {

    // -------------------------------------------------------------------------
    // Property keys (application.properties)
    // -------------------------------------------------------------------------

    private static final String PROP_BASE_URL = "esmind.model.base-url";
    private static final String PROP_API_KEY = "esmind.model.api-key";
    private static final String PROP_MODEL = "esmind.model.name";
    private static final String PROP_ES_HOST = "esmind.es.host";
    private static final String PROP_ES_PORT = "esmind.es.port";
    private static final String PROP_ES_SCHEME = "esmind.es.scheme";
    private static final String PROP_ES_USER = "esmind.es.username";
    private static final String PROP_ES_PASS = "esmind.es.password";
    private static final String PROP_WORKSPACE = "esmind.workspace";

    // -------------------------------------------------------------------------
    // Env var overrides (take precedence over application.properties)
    // -------------------------------------------------------------------------

    private static final String ENV_API_KEY = "DASHSCOPE_API_KEY";
    private static final String ENV_MODEL = "AGENTSCOPE_MODEL";
    private static final String ENV_ES_HOST = "ES_HOST";
    private static final String ENV_ES_PORT = "ES_PORT";
    private static final String ENV_ES_SCHEME = "ES_SCHEME";
    private static final String ENV_WORKSPACE = "AGENTSCOPE_WORKSPACE";

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private static final String DEFAULT_MODEL = "qwen-max";
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    private static final String DEFAULT_WORKSPACE = ".agentscope/workspace";
    private static final String DEFAULT_ES_HOST = "localhost";
    private static final int DEFAULT_ES_PORT = 9200;
    private static final String DEFAULT_ES_SCHEME = "http";

    private static final String DEFAULT_SESSION_ID = "esmind-default";
    private static final String NEW_SESSION_FLAG = "--new-session";

    // -------------------------------------------------------------------------
    // Entry Point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        printBanner();

        // 1. Load configuration: application.properties ← env var overrides
        Properties props = loadProperties();

        String baseUrl      = props.getProperty(PROP_BASE_URL, DEFAULT_BASE_URL);
        String apiKey       = resolveSecret(props.getProperty(PROP_API_KEY, ""), ENV_API_KEY);
        String modelName    = resolveOverride(props.getProperty(PROP_MODEL, DEFAULT_MODEL), ENV_MODEL);
        String esHost       = resolveOverride(props.getProperty(PROP_ES_HOST, DEFAULT_ES_HOST), ENV_ES_HOST);
        int    esPort       = Integer.parseInt(resolveOverride(
                                props.getProperty(PROP_ES_PORT, String.valueOf(DEFAULT_ES_PORT)), ENV_ES_PORT));
        String esScheme     = resolveOverride(props.getProperty(PROP_ES_SCHEME, DEFAULT_ES_SCHEME), ENV_ES_SCHEME);
        String esUsername   = props.getProperty(PROP_ES_USER, "");
        String esPassword   = props.getProperty(PROP_ES_PASS, "");
        Path workspace      = Paths.get(resolveOverride(
                                props.getProperty(PROP_WORKSPACE, DEFAULT_WORKSPACE), ENV_WORKSPACE));

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: No API key configured.\n"
                    + "Set esmind.model.api-key in application.properties\n"
                    + "or export DASHSCOPE_API_KEY=sk-xxx");
            System.exit(1);
        }

        // 2. Initialise workspace from bundled template files
        System.out.println("[1/4] Initialising workspace at: " + workspace.toAbsolutePath());
        WorkspaceInitializer.init(workspace);

        // 3. Build the LLM model
        System.out.println("[2/4] Connecting to model: " + modelName + " @ " + baseUrl);
        Model model = DashScopeChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(false)
                .build();

        // 4. Build the ES tool
        System.out.println("[3/4] Connecting to Elasticsearch: " + esHost + ":" + esPort);
        EsTool esTool = new EsTool(esHost, esPort, esScheme, esUsername, esPassword);

        // 5. Build the HarnessAgent
        System.out.println("[4/4] Building ESMind HarnessAgent ...\n");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(esTool);

        HarnessAgent agent = HarnessAgent.builder()
                .name("esmind")
                .sysPrompt("You are an Elasticsearch query expert. Translate natural language"
                        + " questions into Elasticsearch DSL queries, execute them, and present"
                        + " results clearly. Always explore the index mappings first before"
                        + " writing queries. Use the skills in workspace/skills/ for guidance.")
                .model(model)
                .workspace(workspace)
                .toolkit(toolkit)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .flushBeforeCompact(true)
                        .build())
                .enableAgentTracingLog(true)
                .build();

        // 6. Parse args and run
        ParsedArgs parsedArgs = parseArgs(args);
        String sessionId = parsedArgs.newSession()
                ? "esmind-" + UUID.randomUUID().toString().substring(0, 8)
                : DEFAULT_SESSION_ID;

        System.out.println("Session ID: " + sessionId);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .build();

        if (parsedArgs.question() != null) {
            // Single-shot mode
            runSingleTurn(agent, parsedArgs.question(), ctx);
            esTool.close();
            return;
        }

        // Interactive chat mode
        System.out.println("\n🔍 ESMind ready. Ask questions in natural language about your");
        System.out.println("Elasticsearch data. Same session retains context across turns.");
        System.out.println("Type 'quit', 'exit', or 'q' to end.\n");
        startInteractiveChat(agent, ctx);

        esTool.close();
    }

    // -------------------------------------------------------------------------
    // Configuration loading
    // -------------------------------------------------------------------------

    /**
     * Loads {@code application.properties} from the classpath.
     * Returns an empty {@link Properties} if the resource is not found.
     */
    static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream is = EsMindAgent.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
            } else {
                System.out.println("[config] application.properties not found on classpath,"
                        + " using defaults + env vars");
            }
        } catch (IOException e) {
            System.err.println("[config] Failed to load application.properties: "
                    + e.getMessage());
        }
        return props;
    }

    /**
     * Returns the env var value if set, otherwise the file value.
     */
    private static String resolveOverride(String fileValue, String envName) {
        String envValue = System.getenv(envName);
        return (envValue != null && !envValue.isBlank()) ? envValue : fileValue;
    }

    /**
     * Returns the env var value (first match), falling back to file value.
     * Tries multiple env var names for the same config key.
     */
    private static String resolveSecret(String fileValue, String... envNames) {
        for (String envName : envNames) {
            String envValue = System.getenv(envName);
            if (envValue != null && !envValue.isBlank()) {
                return envValue;
            }
        }
        return fileValue;
    }

    // -------------------------------------------------------------------------
    // Agent interaction helpers
    // -------------------------------------------------------------------------

    private static void runSingleTurn(HarnessAgent agent, String question, RuntimeContext ctx) {
        try {
            System.out.println("\n> " + question + "\n");
            Msg result = agent.call(
                    Msg.builder().role(MsgRole.USER).textContent(question).build(), ctx).block();
            if (result != null) {
                String text = result.getTextContent();
                if (text != null && !text.isBlank()) {
                    System.out.println(text);
                }
            }
        } catch (Exception e) {
            System.err.println("Error during agent call: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void startInteractiveChat(HarnessAgent agent, RuntimeContext ctx) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String question = scanner.nextLine().strip();
                if (question.isEmpty() || "quit".equalsIgnoreCase(question)
                        || "exit".equalsIgnoreCase(question) || "q".equalsIgnoreCase(question)) {
                    break;
                }
                runSingleTurn(agent, question, ctx);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void printBanner() {
        System.out.println("\n"
                + "╔═══════════════════════════════════════════╗\n"
                + "║    ESMind — NL → Elasticsearch Agent     ║\n"
                + "║    Powered by AgentScope Java Harness     ║\n"
                + "╚═══════════════════════════════════════════╝\n");
    }

    private static ParsedArgs parseArgs(String[] args) {
        boolean newSession = false;
        StringBuilder questionBuilder = new StringBuilder();
        for (String arg : args) {
            if (NEW_SESSION_FLAG.equals(arg)) {
                newSession = true;
                continue;
            }
            if (questionBuilder.length() > 0) {
                questionBuilder.append(' ');
            }
            questionBuilder.append(arg);
        }
        String question = questionBuilder.length() == 0 ? null : questionBuilder.toString();
        return new ParsedArgs(newSession, question);
    }

    private record ParsedArgs(boolean newSession, String question) {
    }
}
