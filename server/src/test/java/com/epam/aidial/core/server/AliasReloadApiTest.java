package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An alias resolves its target on every call, so a config reload that changes the target takes effect on the next
 * call.
 */
class AliasReloadApiTest extends ResourceBaseTest {

    private static final String CONFIG = """
            {
              "models": {
                "first": {"endpoint": "http://localhost:4848/first/chat/completions", "userRoles": ["power-user"]},
                "second": {"endpoint": "http://localhost:4848/second/chat/completions", "userRoles": ["power-user"]}
              },
              "aliases": {
                "assistant": {
                  "presentAs": "model",
                  "userRoles": ["default-user"],
                  "target": {"path": "models/%s"}
                }
              },
              "roles": {"default": {"limits": {}}, "default-user": {"limits": {}}, "power-user": {"limits": {}}},
              "keys": {
                "proxyKey1": {"project": "test", "role": "default"},
                "default-key": {"project": "default-project", "role": "default-user"}
              }
            }
            """;

    private static final String CHAT_RESPONSE = "{\"id\":\"cmpl_1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    @Override
    @SneakyThrows
    protected JsonObject additionalSettingsOverrides() {
        Path config = configFile();
        Files.writeString(config, CONFIG.formatted("first"));
        return new JsonObject().put("config", new JsonObject().put("files", new JsonArray().add(config.toString())));
    }

    @Test
    @SneakyThrows
    void nextCallReachesTheNewTarget() {
        List<String> paths = new ArrayList<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/first/chat/completions", request -> {
                paths.add(request.getPath());
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });
            server.map(HttpMethod.POST, "/second/chat/completions", request -> {
                paths.add(request.getPath());
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });

            assertEquals(200, chat().status());

            Files.writeString(configFile(), CONFIG.formatted("second"));
            Response reload = send(HttpMethod.POST, "/v1/ops/config/reload", null, null, "Authorization", "admin");
            assertEquals(200, reload.status(), reload.body());

            assertEquals(200, chat().status());
        }
        assertEquals(List.of("/first/chat/completions", "/second/chat/completions"), paths);
    }

    private Response chat() {
        return send(HttpMethod.POST, "/openai/deployments/assistant/chat/completions", null,
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "api-key", "default-key", "Content-Type", "application/json");
    }

    private Path configFile() {
        return testDir.resolve("aliases-reload.json");
    }
}
