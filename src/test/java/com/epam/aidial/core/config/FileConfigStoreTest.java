package com.epam.aidial.core.config;

import com.epam.aidial.core.AiDial;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

public class FileConfigStoreTest {

    private FileConfigStore store;

    private static JsonObject SETTINGS;

    @BeforeAll
    public static void beforeAll() throws IOException {
        String file = "aidial.settings.json";
        try (InputStream stream = AiDial.class.getClassLoader().getResourceAsStream(file)) {
            Objects.requireNonNull(stream, "Default resource file with settings is not found");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            SETTINGS = new JsonObject(json);
        }
    }

    @BeforeEach
    public void beforeEach() {
        store = new FileConfigStore(mock(Vertx.class), SETTINGS.getJsonObject("config"));
    }

    @Test
    public void testAssociateDeploymentWithApiKey_DuplicateKey() {
        Config config = new Config();
        Key k1 = new Key();
        k1.setProject("some");
        config.getKeys().put("k1", k1);
        Application app = new Application();
        app.setName("app");
        app.setApiKey("k1");
        store.associateDeploymentWithApiKey(config, app);
        assertNotNull(app.getApiKey());
        assertNotEquals("k1", app.getApiKey());
        assertEquals(2, config.getKeys().size());
        assertEquals(app.getName(), config.getKeys().get(app.getApiKey()).getProject());
        assertNotNull(config.getKeys().get(app.getApiKey()).getKey());
        assertEquals(k1.getProject(), config.getKeys().get("k1").getProject());
    }

    @Test
    public void testAssociateDeploymentWithApiKey_MissedKey() {
        Config config = new Config();
        Key k1 = new Key();
        k1.setProject("some");
        config.getKeys().put("k1", k1);
        Application app = new Application();
        app.setName("app");
        store.associateDeploymentWithApiKey(config, app);
        assertNotNull(app.getApiKey());
        assertNotEquals("k1", app.getApiKey());
        assertEquals(2, config.getKeys().size());
        assertEquals(app.getName(), config.getKeys().get(app.getApiKey()).getProject());
        assertNotNull(config.getKeys().get(app.getApiKey()).getKey());
        assertEquals(k1.getProject(), config.getKeys().get("k1").getProject());
    }

    @Test
    public void testAssociateDeploymentWithApiKey_DifferentKey() {
        Config config = new Config();
        Key k1 = new Key();
        k1.setProject("some");
        config.getKeys().put("k1", k1);
        Application app = new Application();
        app.setName("app");
        app.setApiKey("k2");
        store.associateDeploymentWithApiKey(config, app);
        assertNotNull(app.getApiKey());
        assertNotEquals("k1", app.getApiKey());
        assertEquals(2, config.getKeys().size());
        assertEquals(app.getName(), config.getKeys().get(app.getApiKey()).getProject());
        assertNotNull(config.getKeys().get(app.getApiKey()).getKey());
        assertEquals(k1.getProject(), config.getKeys().get("k1").getProject());
    }

    @Test
    public void testAssociateDeploymentWithApiKey_Reload() {
        String apiKey = null;
        for (int i = 0; i < 3; i++) {
            Config config = new Config();
            Key k1 = new Key();
            k1.setProject("some");
            config.getKeys().put("k1", k1);
            Application app = new Application();
            app.setName("app");

            store.associateDeploymentWithApiKey(config, app);

            if (i == 0) {
                apiKey = app.getApiKey();
            } else {
                assertEquals(apiKey, app.getApiKey());
            }
            assertNotNull(app.getApiKey());
            assertNotEquals("k1", app.getApiKey());
            assertEquals(2, config.getKeys().size());
            assertEquals(app.getName(), config.getKeys().get(app.getApiKey()).getProject());
            assertNotNull(config.getKeys().get(app.getApiKey()).getKey());
            assertEquals(k1.getProject(), config.getKeys().get("k1").getProject());
        }
    }

    @Test
    public void testAssociateDeploymentWithApiKey_ReloadKeyChanged() {
        String apiKey = null;
        for (int i = 0; i < 5; i++) {
            Config config = new Config();
            Key k1 = new Key();
            k1.setProject("some");
            config.getKeys().put("k1", k1);
            Application app = new Application();
            app.setName("app");
            if (i == 2) {
                app.setApiKey("k2");
            }
            if (i == 4) {
                app.setApiKey(null);
            }
            store.associateDeploymentWithApiKey(config, app);
            assertNotNull(app.getApiKey());
            assertNotEquals("k1", app.getApiKey());
            assertEquals(2, config.getKeys().size());
            assertEquals(app.getName(), config.getKeys().get(app.getApiKey()).getProject());
            assertNotNull(config.getKeys().get(app.getApiKey()).getKey());
            assertEquals(k1.getProject(), config.getKeys().get("k1").getProject());
            switch (i) {
                case 1 -> assertEquals(apiKey, app.getApiKey());
                case 2, 3 -> assertEquals("k2", app.getApiKey());
                case 4 -> assertNotEquals(apiKey, app.getApiKey());
                default -> apiKey = app.getApiKey();
            }
        }
    }
}
