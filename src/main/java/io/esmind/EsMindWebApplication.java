package io.esmind;

import io.esmind.ast.ASTBuilder;
import io.esmind.compiler.EsRestClient;
import io.esmind.compiler.SchemaLoader;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.renderer.DSLRenderer;
import io.esmind.renderer.ResultTransformer;
import io.esmind.semantic.SemanticParser;
import io.esmind.template.TemplateEngine;
import io.esmind.validator.QueryValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * ESMind Web Application (v2 Compiler).
 *
 * <p>Bypasses HarnessAgent entirely. The query pipeline:
 * <pre>
 *   NL Query → SemanticParser (entity extraction, 500 tokens max)
 *           → ASTBuilder (TemplateEngine + StrategySelector)
 *           → DSLRenderer (AST → ES DSL JSON)
 *           → EsRestClient (execute against ES)
 *           → ResultTransformer (format for display)
 * </pre>
 *
 * <p>Total latency: ~3-5 seconds per query (not 73+ seconds like v1 HarnessAgent).
 */
@SpringBootApplication
public class EsMindWebApplication {

    private static final Logger log = LoggerFactory.getLogger(EsMindWebApplication.class);

    // -------------------------------------------------------------------------
    // Property keys
    // -------------------------------------------------------------------------
    static final String PROP_BASE_URL = "esmind.model.base-url";
    static final String PROP_API_KEY = "esmind.model.api-key";
    static final String PROP_MODEL = "esmind.model.name";
    static final String PROP_ES_HOST = "esmind.es.host";
    static final String PROP_ES_PORT = "esmind.es.port";
    static final String PROP_ES_SCHEME = "esmind.es.scheme";
    static final String PROP_ES_INDEX = "esmind.es.index";
    static final String PROP_CACHE_PATH = "esmind.schema.cache-path";

    static final String ENV_API_KEY = "DASHSCOPE_API_KEY";
    static final String ENV_ES_HOST = "ES_HOST";
    static final String ENV_ES_PORT = "ES_PORT";

    static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    static final String DEFAULT_MODEL = "deepseek-chat";
    static final String DEFAULT_ES_HOST = "localhost";
    static final int DEFAULT_ES_PORT = 9200;
    static final String DEFAULT_ES_SCHEME = "http";
    static final String DEFAULT_CACHE = "data/schema-cache.json";

    public static void main(String[] args) {
        SpringApplication.run(EsMindWebApplication.class, args);
    }

    // -------------------------------------------------------------------------
    // Spring Configuration — v2 Compiler beans
    // -------------------------------------------------------------------------

    @Configuration
    static class CompilerConfig {

        @Bean
        Properties esmindProperties() throws IOException {
            return loadProperties();
        }

        /**
         * Elasticsearch REST Client (lightweight, no AgentScope dependency).
         */
        @Bean
        EsRestClient esRestClient(@Qualifier("esmindProperties") Properties props) {
            String host = resolveOverride(props.getProperty(PROP_ES_HOST, DEFAULT_ES_HOST), ENV_ES_HOST);
            int port = Integer.parseInt(resolveOverride(
                    props.getProperty(PROP_ES_PORT, String.valueOf(DEFAULT_ES_PORT)), ENV_ES_PORT));
            String scheme = props.getProperty(PROP_ES_SCHEME, DEFAULT_ES_SCHEME);
            EsRestClient client = new EsRestClient(host, port, scheme);
            log.info("EsRestClient initialized: {}://{}:{}", scheme, host, port);
            return client;
        }

        /**
         * SchemaRegistry — loads ES mapping (from cache or live), serves field metadata.
         * SchemaLoader.load() handles caching internally.
         */
        @Bean
        SchemaRegistry schemaRegistry(EsRestClient esClient, @Qualifier("esmindProperties") Properties props) throws Exception {
            String indexName = props.getProperty(PROP_ES_INDEX);
            if (indexName == null || indexName.isBlank()) {
                throw new IllegalStateException("esmind.es.index must be set in application.properties");
            }
            SchemaRegistry registry = new SchemaLoader(indexName, esClient).load();
            log.info("SchemaRegistry ready: {} fields, {} nested tables",
                    registry.size(), registry.getNestedPaths().size());
            return registry;
        }

        /**
         * SemanticParser — small LLM call (500 max tokens) for entity extraction.
         * Only extracts intent + entity type/value — NO ES DSL generation.
         */
        @Bean
        SemanticParser semanticParser(@Qualifier("esmindProperties") Properties props,
                                      SchemaRegistry schemaRegistry) {
            String baseUrl = props.getProperty(PROP_BASE_URL, DEFAULT_BASE_URL);
            String apiKey = resolveSecret(props.getProperty(PROP_API_KEY, ""), ENV_API_KEY);
            String modelName = props.getProperty(PROP_MODEL, DEFAULT_MODEL);
            log.info("SemanticParser initialized: model={} @ {} (schema={})",
                    modelName, baseUrl, schemaRegistry != null ? schemaRegistry.size() + " fields" : "none");
            return new SemanticParser(baseUrl, apiKey, modelName, schemaRegistry);
        }

        @Bean
        TemplateEngine queryTemplateEngine(SchemaRegistry schemaRegistry) {
            return new TemplateEngine(schemaRegistry);
        }

        @Bean
        ASTBuilder astBuilder() {
            return new ASTBuilder();
        }

        @Bean
        DSLRenderer dslRenderer() {
            return new DSLRenderer();
        }

        @Bean
        QueryValidator queryValidator(SchemaRegistry schema) {
            return new QueryValidator(schema);
        }

        @Bean
        ResultTransformer resultTransformer() {
            return new ResultTransformer();
        }

        /**
         * The index name used for all queries.
         */
        @Bean
        String esmindIndexName(@Qualifier("esmindProperties") Properties props) {
            return props.getProperty(PROP_ES_INDEX);
        }
    }

    // -------------------------------------------------------------------------
    // Utility methods
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
