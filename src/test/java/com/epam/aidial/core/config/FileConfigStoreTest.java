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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    public void testAssignApiKey() {
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignApiKey(apiKeyData);
        assertNotNull(apiKeyData.getPerRequestKey());
    }

    @Test
    public void testGetApiKeyData() {
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignApiKey(apiKeyData);

        assertNotNull(apiKeyData.getPerRequestKey());

        ApiKeyData res1  = store.getApiKeyData(apiKeyData.getPerRequestKey());
        assertEquals(apiKeyData, res1);

        ApiKeyData res2  = store.getApiKeyData("proxyKey1");
        assertNotNull(res2);
        assertNotNull(res2.getOriginalKey());
        assertEquals("EPM-RTC-GPT", res2.getOriginalKey().getProject());

        assertNull(store.getApiKeyData("unknown-key"));
    }

    @Test
    public void testInvalidateApiKey() {
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignApiKey(apiKeyData);

        assertNotNull(apiKeyData.getPerRequestKey());

        store.invalidateApiKey(apiKeyData);

        assertNull(store.getApiKeyData(apiKeyData.getPerRequestKey()));
    }
}
