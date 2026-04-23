package com.coredb.config;

import com.coredb.api.CoreDBConfig;
import com.coredb.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads CoreDB configuration from a YAML file (flat key: value format).
 *
 * Resolution order:
 *   1. Path passed to load(String)            — filesystem
 *   2. DEFAULT_FILE ("coredb.yaml")           — filesystem, current directory
 *   3. DEFAULT_FILE on the classpath          — bundled inside the jar
 *   4. Built-in defaults                      — no file required
 *
 * Supported keys and defaults:
 *   data-path    : data/core.db
 *   engine-type  : BTREE
 *   page-size    : 8192
 */
public final class CoreDBConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(CoreDBConfigLoader.class);

    public static final String DEFAULT_FILE = "coredb.yaml";

    private static final String KEY_DATA_PATH   = "data-path";
    private static final String KEY_ENGINE_TYPE = "engine-type";
    private static final String KEY_PAGE_SIZE   = "page-size";

    private static final String DEFAULT_DATA_PATH   = "data/core.db";
    private static final String DEFAULT_ENGINE_TYPE = "BTREE";
    private static final int    DEFAULT_PAGE_SIZE   = Constants.PAGE_SIZE;

    private CoreDBConfigLoader() {}

    public record LoadedConfig(String dataPath, CoreDBConfig config) {}

    /** Load from the default file name, falling back to built-in defaults. */
    public static LoadedConfig load() {
        return load(DEFAULT_FILE);
    }

    /**
     * Load from {@code yamlFile}.
     * If the file does not exist or cannot be read, built-in defaults apply.
     */
    public static LoadedConfig load(String yamlFile) {
        Map<String, String> values = readYaml(yamlFile);

        String dataPath    = values.getOrDefault(KEY_DATA_PATH, DEFAULT_DATA_PATH);
        EngineType engine  = parseEngineType(values.getOrDefault(KEY_ENGINE_TYPE, DEFAULT_ENGINE_TYPE));
        int pageSize       = parsePageSize(values.getOrDefault(KEY_PAGE_SIZE, String.valueOf(DEFAULT_PAGE_SIZE)));

        CoreDBConfig config = CoreDBConfig.builder()
                .engineType(engine)
                .pageSize(pageSize)
                .build();

        return new LoadedConfig(dataPath, config);
    }

    // --- YAML loading ---

    private static Map<String, String> readYaml(String file) {
        // 1. Filesystem — exact path given
        Path path = Path.of(file);
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                Map<String, String> values = parseFlat(in);
                log.info("Loaded config: {}", path.toAbsolutePath());
                return values;
            } catch (IOException e) {
                log.warn("Could not read {}: {}", path, e.getMessage());
            }
        }

        // 2. Classpath — works whether running from IDE or packaged jar
        try (InputStream in = CoreDBConfigLoader.class.getClassLoader().getResourceAsStream(file)) {
            if (in != null) {
                Map<String, String> values = parseFlat(in);
                log.info("Loaded config from classpath: {}", file);
                return values;
            }
        } catch (IOException e) {
            log.warn("Could not read classpath resource {}: {}", file, e.getMessage());
        }

        // 3. Built-in defaults
        if (file.equals(DEFAULT_FILE)) {
            log.info("No {} found — using built-in defaults", DEFAULT_FILE);
        } else {
            log.warn("Config file '{}' not found — using built-in defaults", file);
        }
        return Map.of();
    }

    /**
     * Minimal flat YAML parser.
     * Handles: "key: value", blank lines, and comment lines starting with '#'.
     * Does not handle nested blocks, lists, or multi-line values.
     */
    private static Map<String, String> parseFlat(InputStream in) throws IOException {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon < 1) {
                    continue;
                }
                String key   = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                // Strip inline comment (e.g. "BTREE  # default engine")
                int commentMark = value.indexOf('#');
                if (commentMark >= 0) {
                    value = value.substring(0, commentMark).trim();
                }
                if (!key.isEmpty()) {
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    // --- Value parsers ---

    private static EngineType parseEngineType(String raw) {
        try {
            return EngineType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown engine-type '{}', falling back to BTREE", raw);
            return EngineType.BTREE;
        }
    }

    private static int parsePageSize(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid page-size '{}', falling back to {}", raw, DEFAULT_PAGE_SIZE);
            return DEFAULT_PAGE_SIZE;
        }
    }
}
