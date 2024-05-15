package com.epam.aidial.core.config;

import com.epam.aidial.core.security.ApiKeyStore;
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
import java.util.Map;

import static com.epam.aidial.core.config.Config.ASSISTANT;


@Slf4j
public final class FileConfigStore implements ConfigStore {

    private final String[] paths;
    private volatile Config config;
    private final ApiKeyStore apiKeyStore;

    public FileConfigStore(Vertx vertx, JsonObject settings, ApiKeyStore apiKeyStore) {
        this.apiKeyStore = apiKeyStore;
        this.paths = settings.getJsonArray("files")
                .stream().map(path -> (String) path).toArray(String[]::new);

        long period = settings.getLong("reload");
        load(true);
        vertx.setPeriodic(period, period, event -> load(false));
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

            apiKeyStore.addProjectKeys(config.getKeys());

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

    private Config loadConfig() throws Exception {
        JsonNode tree = ProxyUtil.MAPPER.createObjectNode();

        for (String path : paths) {
            try (InputStream stream = openStream(path)) {
                tree = ProxyUtil.MAPPER.readerForUpdating(tree).readTree(stream);
            }
        }

        return ProxyUtil.MAPPER.convertValue(tree, Config.class);
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
    }
}
