package com.epam.deltix.dial.proxy.config;

import com.epam.deltix.dial.proxy.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;


@Slf4j
public final class FileConfigStore implements ConfigStore {

    private final String[] paths;
    private volatile Config config;

    public FileConfigStore(Vertx vertx, JsonObject settings) {
        this.paths = settings.getJsonArray("files", JsonArray.of("proxy.config.json"))
                .stream().map(path -> (String) path).toArray(String[]::new);

        long period = settings.getLong("reload", 60000L);
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

            for (Map.Entry<String, Assistant> entry : config.getAssistant().getAssistants().entrySet()) {
                String name = entry.getKey();
                Assistant assistant = entry.getValue();
                assistant.setName(name);

                if (assistant.getEndpoint() == null) {
                    assistant.setEndpoint(config.getAssistant().getEndpoint());
                }
            }

            for (Map.Entry<String, Application> entry : config.getApplications().entrySet()) {
                String name = entry.getKey();
                Application application = entry.getValue();
                application.setName(name);
            }

            for (Map.Entry<String, Key> entry : config.getKeys().entrySet()) {
                String key = entry.getKey();
                Key value = entry.getValue();
                value.setKey(key);
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
}
