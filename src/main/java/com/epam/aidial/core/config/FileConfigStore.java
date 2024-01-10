package com.epam.aidial.core.config;

import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static com.epam.aidial.core.config.Config.ASSISTANT;
import static com.epam.aidial.core.security.ApiKeyGenerator.generateKey;


@Slf4j
public final class FileConfigStore implements ConfigStore {

    private final String[] paths;
    private volatile Config config;
    private final Map<String, ApiKeyData> keys = new HashMap<>();

    public FileConfigStore(Vertx vertx, JsonObject settings) {
        this.paths = settings.getJsonArray("files")
                .stream().map(path -> (String) path).toArray(String[]::new);

        long period = settings.getLong("reload");
        load(true);
        vertx.setPeriodic(period, period, event -> load(false));
    }

    @Override
    public void assignApiKey(ApiKeyData data) {
        synchronized (keys) {
            String apiKey = generateApiKey();
            keys.put(apiKey, data);
            data.setApiKey(apiKey);
        }
    }

    @Override
    public ApiKeyData getApiKeyData(String key) {
        synchronized (keys) {
            return keys.get(key);
        }
    }

    @Override
    public void invalidateApiKey(ApiKeyData apiKeyData) {
        synchronized (keys) {
            if (apiKeyData.isPerRequestKey()) {
                keys.remove(apiKeyData.getApiKey());
            }
        }
    }

    @Override
    public Config load() {
        return config;
    }

    @SneakyThrows
    private void load(boolean fail) {
        try {
            Config config = loadConfig();

            for (Map.Entry<String, Route> entry : config.getRoutes().entrySet()) {
                String name = entry.getKey();
                Route route = entry.getValue();
                route.setName(name);
            }

            for (Map.Entry<String, Model> entry : config.getModels().entrySet()) {
                String name = entry.getKey();
                Model model = entry.getValue();
                model.setName(name);
            }

            for (Map.Entry<String, Addon> entry : config.getAddons().entrySet()) {
                String name = entry.getKey();
                Addon addon = entry.getValue();
                addon.setName(name);
            }

            Assistants assistants = config.getAssistant();
            for (Map.Entry<String, Assistant> entry : assistants.getAssistants().entrySet()) {
                String name = entry.getKey();
                Assistant assistant = entry.getValue();
                assistant.setName(name);

                if (assistant.getEndpoint() == null) {
                    assistant.setEndpoint(assistants.getEndpoint());
                }

                setMissingFeatures(assistant, assistants.getFeatures());
            }
            // base assistant
            if (assistants.getEndpoint() != null) {
                Assistant baseAssistant = new Assistant();
                baseAssistant.setName(ASSISTANT);
                baseAssistant.setEndpoint(assistants.getEndpoint());
                baseAssistant.setFeatures(assistants.getFeatures());
                assistants.getAssistants().put(ASSISTANT, baseAssistant);
            }

            for (Map.Entry<String, Application> entry : config.getApplications().entrySet()) {
                String name = entry.getKey();
                Application application = entry.getValue();
                application.setName(name);
            }

            synchronized (keys) {
                Map<String, String> keysToBeReplaced = new HashMap<>();
                for (Map.Entry<String, Key> entry : config.getKeys().entrySet()) {
                    String key = entry.getKey();
                    Key value = entry.getValue();
                    if (keys.containsKey(key)) {
                        key = generateApiKey();
                        keysToBeReplaced.put(entry.getKey(), key);
                    }
                    value.setKey(key);
                    ApiKeyData apiKeyData = new ApiKeyData();
                    apiKeyData.setOriginalKey(value);
                    keys.put(key, apiKeyData);
                }
                for (Map.Entry<String, String> entry : keysToBeReplaced.entrySet()) {
                    config.getKeys().remove(entry.getKey());
                    config.getKeys().put(entry.getValue(), keys.get(entry.getValue()).getOriginalKey());
                }
            }

            for (Map.Entry<String, Role> entry : config.getRoles().entrySet()) {
                String name = entry.getKey();
                Role role = entry.getValue();
                role.setName(name);
            }

            this.config = config;
        } catch (Throwable e) {
            if (fail) {
                throw e;
            }

            log.warn("Failed to reload config: {}", e.getMessage());
        }
    }

    private String generateApiKey() {
        String apiKey = generateKey();
        while (keys.containsKey(apiKey)) {
            log.warn("duplicate API key is found. Trying to generate a new one");
            apiKey = generateKey();
        }
        return apiKey;
    }

    private Config loadConfig() throws Exception {
        JsonNode tree = null;

        for (String path : paths) {
            try (InputStream stream = openStream(path)) {
                if (tree == null) {
                    tree = ProxyUtil.MAPPER.readTree(stream);
                } else {
                    tree = ProxyUtil.MAPPER.readerForUpdating(tree).readTree(stream);
                }
            }
        }

        return ProxyUtil.MAPPER.convertValue(tree, Config.class);
    }

    private static InputStream openStream(String path) {
        try {
            return new BufferedInputStream(new FileInputStream(path));
        } catch (FileNotFoundException e) {
            return ConfigStore.class.getClassLoader().getResourceAsStream(path);
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
    }
}
