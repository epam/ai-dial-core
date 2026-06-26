package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.validation.ValidationModule;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * File-backed {@link ConfigStore}. Loads the dynamic config from one or more JSON files on startup and
 * reloads them periodically. Also performs best-effort legacy {@code endpoint}/{@code responsesEndpoint}
 * → {@code interfaces} write-back (Layer B), gated by the {@code config.migrateLegacyEndpoints} setting.
 */
@Slf4j
public final class FileConfigStore implements ConfigStore {

    private static final String MIGRATE_LEGACY_ENDPOINTS_SETTING = "migrateLegacyEndpoints";

    // Conventional pretty printer for config write-back: 2-space indent, multi-line arrays, and a
    // `"key": value` separator (no space before the colon, unlike Jackson's default `"key" : value`).
    private static final DefaultPrettyPrinter PRETTY_PRINTER = createPrettyPrinter();

    private final JsonMapper jsonMapper;
    private final String[] paths;
    private volatile Config config;
    @Nullable
    private final ApiKeyStore apiKeyStore;
    private final List<Consumer<Config>> onReloadCallbacks;
    private final boolean migrateConfigFile;

    public FileConfigStore(Vertx vertx, JsonObject settings, @Nullable ApiKeyStore apiKeyStore,
                           List<Consumer<Config>> initialOnReloadCallbacks) {
        // Config-file write-back (Layer B) is opt-in via the `config.migrateLegacyEndpoints` setting.
        this(vertx, settings, apiKeyStore, initialOnReloadCallbacks,
                settings.getBoolean(MIGRATE_LEGACY_ENDPOINTS_SETTING, false));
    }

    // Package-private seam: lets tests drive the write-back gate without setting an env var.
    FileConfigStore(Vertx vertx, JsonObject settings, @Nullable ApiKeyStore apiKeyStore,
                    List<Consumer<Config>> initialOnReloadCallbacks, boolean migrateConfigFile) {
        this.jsonMapper = buildJsonMapper(settings);
        this.apiKeyStore = apiKeyStore;
        this.onReloadCallbacks = List.copyOf(initialOnReloadCallbacks);
        this.paths = settings.getJsonArray("files")
                .stream().map(path -> (String) path).toArray(String[]::new);
        this.migrateConfigFile = migrateConfigFile;

        long period = settings.getLong("reload");
        load(true);
        vertx.setPeriodic(period, period, event -> load(false));
    }

    @Override
    public Config get() {
        return config;
    }

    @Override
    public Config reload() {
        return load(true);
    }

    @SneakyThrows
    private Config load(boolean fail) {
        try {
            log.debug("Config loading is started");
            Config config = loadConfig();

            ConfigPostProcessor.process(config, apiKeyStore);

            this.config = config;
            for (Consumer<Config> callback : onReloadCallbacks) {
                callback.accept(config);
            }
            log.debug("Config loading is completed");
            return config;
        } catch (Throwable e) {
            if (fail) {
                throw e;
            }

            log.warn("Failed to reload config: {}", e.getMessage());
        }
        return null;
    }

    private Config loadConfig() throws Exception {
        JsonNode tree = jsonMapper.createObjectNode();

        for (String path : paths) {
            JsonNode fileTree;
            try (InputStream stream = openStream(path)) {
                fileTree = jsonMapper.readTree(stream);
            }
            if (fileTree == null || fileTree.isMissingNode()) {
                continue;
            }

            // Legacy `endpoint` migration. With write-back enabled, rewrite the raw tree to the interfaces
            // shape and persist it; otherwise leave the file untouched and only warn. Either way the
            // in-memory POJO is migrated later by Layer A (ConfigPostProcessor.migrateInterfaces).
            if (fileTree instanceof ObjectNode root) {
                if (migrateConfigFile) {
                    if (InterfaceMigration.migrateRawTree(root)) {
                        writeBackIfPossible(path, root);
                    }
                } else if (InterfaceMigration.hasLegacyEndpoints(root)) {
                    log.warn("The `endpoint` and `responsesEndpoint` config in '{}' is obsolete and should be "
                            + "migrated to `interfaces` config. To perform automatic on-disk migration set the "
                            + "`config.{}` setting to `true`.", path, MIGRATE_LEGACY_ENDPOINTS_SETTING);
                }
            }

            tree = jsonMapper.readerForUpdating(tree).readTree(jsonMapper.treeAsTokens(fileTree));
        }

        Config config = jsonMapper.convertValue(tree, Config.class);
        rejectCanonicalShapedKeys(config);
        return config;
    }

    /**
     * Best-effort write-back of a migrated config tree. Only real on-disk regular writable files are
     * rewritten; classpath resources, non-regular files, and read-only mounts (e.g. k8s ConfigMap) are
     * skipped with an info log. The write is atomic (temp file in the same dir + {@code ATOMIC_MOVE}).
     * Any {@link IOException} degrades to in-memory-only migration.
     */
    private void writeBackIfPossible(String path, JsonNode migratedTree) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            log.warn("Config file {} requires interfaces migration but is not an on-disk regular file "
                    + "(classpath/missing); in-memory only", path);
            return;
        }
        if (!Files.isWritable(file)) {
            log.warn("Config file {} requires interfaces migration but is read-only; in-memory only", path);
            return;
        }
        try {
            byte[] content = jsonMapper.writer(PRETTY_PRINTER).writeValueAsBytes(migratedTree);
            Path dir = file.toAbsolutePath().getParent();
            Path temp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
            try {
                Files.write(temp, content);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            log.info("Migrated config file {} to interfaces", path);
        } catch (IOException e) {
            log.warn("Failed to write back interfaces migration for config file {}; in-memory only: {}",
                    path, e.getMessage());
        }
    }

    // Canonical-ID shape "{typeSegment}/{bucket}/{name}" is reserved for API-managed entries
    // in the merged config (MergedConfigStore.canonicalId). File map keys with this shape would
    // collide with the API-origin discriminator used by listing/source projections, so reject
    // them at load time rather than carry an ambiguous origin signal downstream.
    private static void rejectCanonicalShapedKeys(Config config) {
        rejectShape("models", config.getModels());
        rejectShape("interceptors", config.getInterceptors());
        rejectShape("roles", config.getRoles());
        rejectShape("keys", config.getKeys());
        rejectShape("routes", config.getRoutes());
    }

    private static void rejectShape(String typeSegment, Map<String, ?> map) {
        String prefix = typeSegment + "/";
        for (String key : map.keySet()) {
            if (key.startsWith(prefix) && key.indexOf('/', prefix.length()) != -1) {
                throw new IllegalArgumentException(
                        "File config '" + typeSegment + "' key '" + key + "' has the reserved "
                                + "canonical-ID shape '" + typeSegment + "/<bucket>/<name>'. "
                                + "Use a simple name for file-defined entries; canonical IDs are "
                                + "reserved for API-managed entries via the Configuration API.");
            }
        }
    }

    @SneakyThrows
    private static InputStream openStream(String path) {
        try {
            return new BufferedInputStream(new FileInputStream(path));
        } catch (FileNotFoundException e) {
            InputStream stream = ConfigStore.class.getClassLoader().getResourceAsStream(path);
            if (stream == null) {
                throw new FileNotFoundException("File not found: " + path);
            }
            return stream;
        }
    }

    private JsonMapper buildJsonMapper(JsonObject settings) {
        JsonMapper mapper = JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .addModule(new ValidationModule())
                .build();

        boolean overwriteArrays = settings
                .getJsonObject("jsonMergeStrategy", new JsonObject())
                .getBoolean("overwriteArrays", false);

        mapper.configOverride(ArrayNode.class)
                .setMergeable(!overwriteArrays);

        return mapper;
    }

    private static DefaultPrettyPrinter createPrettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }
}
