package io.esmind.agent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes the agent workspace by copying bundled template files from the classpath into a
 * target directory on disk.
 *
 * <p>The template files live under {@code src/main/resources/workspace/} and are packaged inside
 * the JAR. When the example is run for the first time, {@link #init(Path)} extracts them into the
 * given workspace directory so the agent can read and modify them at runtime.
 *
 * <p>Workspace structure:
 * <pre>
 * &lt;workspace&gt;/
 * ├── AGENTS.md              # Agent persona and core rules (always loaded)
 * ├── MEMORY.md              # Persistent notes accumulated across sessions
 * ├── knowledge/
 * │   └── KNOWLEDGE.md       # Elasticsearch cluster index mapping reference
 * ├── skills/
 * │   ├── query-writing/     # How to construct ES DSL queries
 * │   ├── schema-exploration/ # How to discover index structure
 * │   └── query-debugging/   # How to debug ES query issues
 * └── subagents/
 *     ├── dsl-writer.md      # Specialised subagent for DSL generation
 *     └── result-analyzer.md # Specialised subagent for result analysis
 * </pre>
 */
public class WorkspaceInitializer {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceInitializer.class);
    private static final String CLASSPATH_PREFIX = "workspace";

    /**
     * Copies all bundled workspace template files into {@code targetDir}.
     *
     * <p>Existing files are left unchanged so that previously accumulated notes survive restarts.
     * New template files are copied with {@link StandardCopyOption#REPLACE_EXISTING} disabled.
     *
     * @param targetDir directory to initialise; created if it does not exist
     * @throws IOException if a file cannot be read or written
     */
    public static void init(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        URL resourceUrl = WorkspaceInitializer.class.getClassLoader().getResource(CLASSPATH_PREFIX);
        if (resourceUrl == null) {
            log.warn("Classpath resource '{}' not found — workspace will not be pre-populated.",
                    CLASSPATH_PREFIX);
            return;
        }

        URI resourceUri;
        try {
            resourceUri = resourceUrl.toURI();
        } catch (URISyntaxException e) {
            throw new IOException("Cannot convert resource URL to URI: " + resourceUrl, e);
        }

        if ("jar".equals(resourceUri.getScheme())) {
            try (FileSystem fs = FileSystems.newFileSystem(resourceUri, Collections.emptyMap())) {
                Path source = fs.getPath(CLASSPATH_PREFIX);
                copyTree(source, targetDir);
            }
        } else {
            Path source = Path.of(resourceUri);
            copyTree(source, targetDir);
        }

        log.info("Workspace initialised at {}", targetDir);
    }

    private static void copyTree(Path source, Path targetDir) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path srcPath : (Iterable<Path>) walk::iterator) {
                Path relative = source.relativize(srcPath);
                Path target = targetDir.resolve(relative.toString());

                if (Files.isDirectory(srcPath)) {
                    Files.createDirectories(target);
                } else if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = Files.newInputStream(srcPath)) {
                        Files.copy(in, target);
                    }
                    log.debug("Copied workspace file: {}", relative);
                } else {
                    log.debug("Skipped (already exists): {}", relative);
                }
            }
        }
    }
}
