package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.validation.ValidationModule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

@Slf4j
public final class FileConfigStore implements ConfigStore {

    private final JsonMapper jsonMapper;
    private final String[] paths;
    private volatile Config config;
    @Nullable
    private final ApiKeyStore apiKeyStore;
    private final List<Consumer<Config>> onReloadCallbacks;

    public FileConfigStore(Vertx vertx, JsonObject settings, @Nullable ApiKeyStore apiKeyStore,
                           List<Consumer<Config>> initialOnReloadCallbacks) {
        this.jsonMapper = buildJsonMapper(settings);
        this.apiKeyStore = apiKeyStore;
        this.onReloadCallbacks = List.copyOf(initialOnReloadCallbacks);
        this.paths = settings.getJsonArray("files")
                .stream().map(path -> (String) path).toArray(String[]::new);

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
            try (InputStream stream = openStream(path)) {
                tree = jsonMapper.readerForUpdating(tree).readTree(stream);
            }
        }

        Config config = jsonMapper.convertValue(tree, Config.class);
        rejectCanonicalShapedKeys(config);
        return config;
    }

    // Canonical-ID shape "{typeSegment}/{bucket}/{name}" is reserved for API-managed entries
    // in the merged config (MergedConfigStore.canonicalId). File map keys with this shape would
    // collide with the API-origin discriminator used by listing/source projections, so reject
    // them at load time rather than carry an ambiguous origin signal downstream.
    private static final Pattern CANONICAL_ID_SHAPE = Pattern.compile("^[^/]+/[^/]+/.+$");

    private static void rejectCanonicalShapedKeys(Config config) {
        rejectShape("models", config.getModels());
        rejectShape("interceptors", config.getInterceptors());
        rejectShape("roles", config.getRoles());
        rejectShape("keys", config.getKeys());
        rejectShape("routes", config.getRoutes());
    }

    private static void rejectShape(String typeSegment, Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        String prefix = typeSegment + "/";
        for (String key : map.keySet()) {
            if (key != null && key.startsWith(prefix) && CANONICAL_ID_SHAPE.matcher(key).matches()) {
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

    private static void setMissingFeatures(Deployment model, Features features) {
        if (features == null) {
            return;
        }

        Features modelFeatures = model.getFeatures();
        if (modelFeatures == null) {
            model.setFeatures(features);
            return;
        }

        if (modelFeatures.getRateEndpoint() == null) {
            modelFeatures.setRateEndpoint(features.getRateEndpoint());
        }
        if (modelFeatures.getTokenizeEndpoint() == null) {
            modelFeatures.setTokenizeEndpoint(features.getTokenizeEndpoint());
        }
        if (modelFeatures.getTruncatePromptEndpoint() == null) {
            modelFeatures.setTruncatePromptEndpoint(features.getTruncatePromptEndpoint());
        }
        if (modelFeatures.getSystemPromptSupported() == null) {
            modelFeatures.setSystemPromptSupported(features.getSystemPromptSupported());
        }
        if (modelFeatures.getToolsSupported() == null) {
            modelFeatures.setToolsSupported(features.getToolsSupported());
        }
        if (modelFeatures.getSeedSupported() == null) {
            modelFeatures.setSeedSupported(features.getSeedSupported());
        }
        if (modelFeatures.getUrlAttachmentsSupported() == null) {
            modelFeatures.setUrlAttachmentsSupported(features.getUrlAttachmentsSupported());
        }
        if (modelFeatures.getFolderAttachmentsSupported() == null) {
            modelFeatures.setFolderAttachmentsSupported(features.getFolderAttachmentsSupported());
        }
        if (modelFeatures.getAllowResume() == null) {
            modelFeatures.setAllowResume(features.getAllowResume());
        }
        if (modelFeatures.getAccessibleByPerRequestKey() == null) {
            modelFeatures.setAccessibleByPerRequestKey(features.getAccessibleByPerRequestKey());
        }
        if (modelFeatures.getContentPartsSupported() == null) {
            modelFeatures.setContentPartsSupported(features.getContentPartsSupported());
        }
        if (modelFeatures.getTemperatureSupported() == null) {
            modelFeatures.setTemperatureSupported(features.getTemperatureSupported());
        }
        if (modelFeatures.getCacheSupported() == null) {
            modelFeatures.setCacheSupported(features.getCacheSupported());
        }
        if (modelFeatures.getAutoCachingSupported() == null) {
            modelFeatures.setAutoCachingSupported(features.getAutoCachingSupported());
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
}
