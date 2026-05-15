package io.esmind.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * <h2>Prerequisites</h2>
 *
 * <ol>
 *   <li>JDK 17+, Maven 3.8+
 *   <li>Set environment variable {@code DASHSCOPE_API_KEY} (or {@code OPENAI_API_KEY})
 *   <li>Accessible Elasticsearch instance (7.x+)
 * </ol>
 *
 * <h2>Run</h2>
 *
 * <pre>
 * # Interactive mode
 * export DASHSCOPE_API_KEY=sk-xxx
 * mvn compile exec:java -Dexec.mainClass="io.esmind.agent.EsMindAgent"
 *
 * # Single query
 * mvn compile exec:java \\
 *   -Dexec.mainClass="io.esmind.agent.EsMindAgent" \\
 *   -Dexec.args="上个月销量最高的产品是什么？"
 * </pre>
 */
public class EsMindAgent {

    // -------------------------------------------------------------------------
    // Environment variable names
    // -------------------------------------------------------------------------

    /** DashScope / LLM API key. */
    public static final String ENV_API_KEY = "DASHSCOPE_API_KEY";

    /** LLM model name. Defaults to {@code qwen-max}. */
    public static final String ENV_MODEL_NAME = "AGENTSCOPE_MODEL";

    /** Elasticsearch connection details. */
    public static final String ENV_ES_HOST = "ES_HOST";
    public static final String ENV_ES_PORT = "ES_PORT";
    public static final String ENV_ES_SCHEME = "ES_SCHEME";
    public static final String ENV_ES_USERNAME = "ES_USERNAME";
    public static final String ENV_ES_PASSWORD = "ES_PASSWORD";

    /** Workspace directory. Defaults to {@code .agentscope/workspace}. */
    public static final String ENV_WORKSPACE = "AGENTSCOPE_WORKSPACE";

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private static final String DEFAULT_MODEL = "qwen-max";
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

        // 1. Resolve configuration from environment
        String apiKey = requireEnv(ENV_API_KEY);
        String modelName = getEnv(ENV_MODEL_NAME, DEFAULT_MODEL);
        Path workspace = Paths.get(getEnv(ENV_WORKSPACE, DEFAULT_WORKSPACE));

        String esHost = getEnv(ENV_ES_HOST, DEFAULT_ES_HOST);
        int esPort = Integer.parseInt(getEnv(ENV_ES_PORT, String.valueOf(DEFAULT_ES_PORT)));
        String esScheme = getEnv(ENV_ES_SCHEME, DEFAULT_ES_SCHEME);
        String esUsername = System.getenv(ENV_ES_USERNAME);
        String esPassword = System.getenv(ENV_ES_PASSWORD);

        // 2. Initialise workspace from bundled template files
        System.out.println("[1/4] Initialising workspace at: " + workspace.toAbsolutePath());
        WorkspaceInitializer.init(workspace);

        // 3. Build the LLM model
        System.out.println("[2/4] Connecting to model: " + modelName);
        Model model = DashScopeChatModel.builder()
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
    // Agent interaction helpers
    // -------------------------------------------------------------------------

    /**
     * Runs a single agent turn with the given question and prints the response.
     */
    private static void runSingleTurn(HarnessAgent agent, String question, RuntimeContext ctx) {
        try {
            System.out.println("\n> " + question + "\n");
            Msg result = agent.call(Msg.userMsg(question), ctx).block();
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

    /**
     * Starts an interactive read-eval loop with the HarnessAgent.
     */
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
        System.out.println("\n" +
                "╔═══════════════════════════════════════════╗\n" +
                "║    ESMind — NL → Elasticsearch Agent     ║\n" +
                "║    Powered by AgentScope Java Harness     ║\n" +
                "╚═══════════════════════════════════════════╝\n");
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

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("Required environment variable '" + name
                    + "' is not set.\nCopy .env.example → .env and fill in your values.");
            System.exit(1);
            return null; // unreachable
        }
        return value;
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private record ParsedArgs(boolean newSession, String question) {
    }
}
