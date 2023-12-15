package com.epam.aidial.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class FileConfigStoreTest {

    @Test
    public void testAssociateDeploymentWithApiKey_DuplicateKey() {
        Config config = new Config();
        Key k1 = new Key();
        k1.setProject("some");
        config.getKeys().put("k1", k1);
        Application app = new Application();
        app.setName("app");
        app.setApiKey("k1");
        FileConfigStore.associateDeploymentWithApiKey(config, app);
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
        FileConfigStore.associateDeploymentWithApiKey(config, app);
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
        FileConfigStore.associateDeploymentWithApiKey(config, app);
        assertNotNull(app.getApiKey());
        assertNotEquals("k1", app.getApiKey());
        assertEquals(2, config.getKeys().size());
        assertEquals(app.getName(), config.getKeys().get(app.getApiKey()).getProject());
        assertNotNull(config.getKeys().get(app.getApiKey()).getKey());
        assertEquals(k1.getProject(), config.getKeys().get("k1").getProject());
    }
}
