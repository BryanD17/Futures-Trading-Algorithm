package com.topstep.trading.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Builds the {@link EngineConfig}. THE ONLY class in {@code trading-engine/src/main}
 * that reads JVM system properties or the {@code ENGINE_*} environment
 * (V5 Agent 01 acceptance: {@code grep -rn "System.getProperty"} returns only
 * this file).
 *
 * <p>Layers (highest first): {@code -D} &gt; {@code ENGINE_<KEY>} env &gt;
 * engine.properties &gt; classpath {@code engine-defaults.properties} &gt; code.
 *
 * <p>engine.properties location: {@code -Dengine.props=<path>} (or env
 * {@code ENGINE_PROPS}) when set — the value {@code NONE} disables the file
 * layer (hermetic unit tests) — otherwise
 * {@code ${user.home}/topstep-trading/engine.properties}. Both
 * {@code ./gradlew bootRun} and {@code java -jar api-backend-*.jar} go through
 * this loader, so both read the same file.
 */
public final class EngineConfigLoader {

    /** System property naming the engine.properties file ({@code NONE} = no file). */
    public static final String PROPS_PATH_PROPERTY = "engine.props";
    /** Classpath resource holding the V5 product defaults. */
    public static final String DEFAULTS_RESOURCE = "engine-defaults.properties";

    private EngineConfigLoader() {}

    /** Load from the real JVM (system properties, environment, user.home). */
    public static EngineConfig load() {
        return load(System.getenv(), EngineConfigLoader::systemProperty);
    }

    /** Load with an injected environment + system-property reader (tests). */
    public static EngineConfig load(Map<String, String> environment,
                                    Function<String, String> systemProperty) {
        Map<String, String> classpath = readClasspathDefaults();

        String explicit = systemProperty.apply(PROPS_PATH_PROPERTY);
        if (explicit == null) explicit = environment.get("ENGINE_PROPS");
        String filePath;
        Map<String, String> file = new LinkedHashMap<>();
        boolean loaded = false;
        if (explicit != null && "NONE".equalsIgnoreCase(explicit.trim())) {
            filePath = "(engine.props=NONE)";
        } else {
            Path p = explicit != null && !explicit.isBlank()
                    ? Paths.get(explicit.trim())
                    : defaultPropertiesPath(systemProperty.apply("user.home"));
            filePath = p.toAbsolutePath().toString();
            if (Files.isRegularFile(p)) {
                try {
                    file.putAll(read(Files.newBufferedReader(p, StandardCharsets.UTF_8)));
                    loaded = true;
                } catch (IOException e) {
                    System.err.println("[EngineConfig] ERROR: cannot read " + filePath + ": " + e.getMessage()
                            + " — continuing WITHOUT the properties file");
                }
            }
        }

        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : environment.entrySet()) {
            if (e.getKey().startsWith("ENGINE_")) env.put(e.getKey(), e.getValue());
        }
        return new EngineConfig(classpath, "classpath:" + DEFAULTS_RESOURCE,
                file, filePath, loaded, env, systemProperty);
    }

    /** {@code <home>/topstep-trading/engine.properties}. */
    public static Path defaultPropertiesPath(String userHome) {
        return Paths.get(userHome == null ? "." : userHome, "topstep-trading", "engine.properties");
    }

    /** {@code user.home} (the engine's data directory root). */
    public static String userHome() {
        return System.getProperty("user.home");
    }

    /** {@code user.dir} (process working directory). */
    public static String userDir() {
        return System.getProperty("user.dir");
    }

    private static String systemProperty(String name) {
        return System.getProperty(name);
    }

    private static Map<String, String> readClasspathDefaults() {
        try (InputStream in = EngineConfigLoader.class.getClassLoader()
                .getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                System.err.println("[EngineConfig] ERROR: classpath " + DEFAULTS_RESOURCE
                        + " missing — code defaults only");
                return Map.of();
            }
            return read(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[EngineConfig] ERROR: cannot read classpath " + DEFAULTS_RESOURCE
                    + ": " + e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, String> read(Reader reader) throws IOException {
        try (Reader r = reader) {
            Properties p = new Properties();
            p.load(r);
            Map<String, String> out = new LinkedHashMap<>();
            for (String k : p.stringPropertyNames()) out.put(k.trim(), p.getProperty(k).trim());
            return out;
        }
    }
}
