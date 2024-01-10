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
        //TODO
    }

    @Test
    public void testGetApiKeyData() {
        //TODO
    }

    @Test
    public void testInvalidateApiKey() {
        //TODO
    }
}
