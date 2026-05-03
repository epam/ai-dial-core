package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 2S.12: admin-only validate endpoint at
 * {@code POST /v1/admin/validate}. Phase 2 scope is model-only — Jackson parse,
 * mask-sentinel rejection, deployment-name uniqueness, upstream URL syntax.
 */
public class AdminValidateApiTest extends ResourceBaseTest {

    private static final String VALID_MODEL_SPEC = """
            {
              "kind": "Model",
              "name": "validate-happy-model",
              "spec": {
                "type": "chat",
                "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
              }
            }
            """;

    @Test
    @SneakyThrows
    void testT1HappyPath() {
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, VALID_MODEL_SPEC,
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertTrue(body.get("valid").asBoolean(), () -> "Expected valid=true: " + response.body());
        assertTrue(body.get("errors").isArray() && body.get("errors").isEmpty(),
                () -> "Expected empty errors: " + response.body());
    }

    @Test
    @SneakyThrows
    void testT2InvalidJsonStructureFailsJackson() {
        // limits is expected as an object on the Deployment entity; passing a string forces a
        // Jackson deserialization failure, which validate surfaces via errors[] not as 400.
        String body = """
                {
                  "kind": "Model",
                  "name": "validate-invalid-json",
                  "spec": {
                    "type": "chat",
                    "limits": "not-an-object"
                  }
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertFalse(parsed.get("valid").asBoolean(), () -> "Expected valid=false: " + response.body());
        assertTrue(parsed.get("errors").isArray() && !parsed.get("errors").isEmpty(),
                () -> "Expected non-empty errors: " + response.body());
    }

    @Test
    @SneakyThrows
    void testT3DeploymentNameCollision() {
        String createBody = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/existing-model/chat/completions"
                }
                """;
        verify(send(HttpMethod.POST, "/v1/models/public/validate-existing-model", null, createBody,
                "authorization", "admin"), 201);
        waitForGet("/v1/models/public/validate-existing-model");

        String validateBody = """
                {
                  "kind": "Model",
                  "name": "validate-existing-model",
                  "spec": {
                    "type": "chat",
                    "endpoint": "http://localhost:7001/openai/deployments/existing-model/chat/completions"
                  }
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, validateBody,
                "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertFalse(parsed.get("valid").asBoolean(), () -> "Expected valid=false: " + response.body());
        JsonNode errors = parsed.get("errors");
        assertTrue(errors.isArray() && !errors.isEmpty(), () -> "Expected non-empty errors: " + response.body());
        assertEquals("name", errors.get(0).get("field").asText(),
                () -> "Expected first error on 'name': " + response.body());
    }

    @Test
    @SneakyThrows
    void testT4MalformedUpstreamUrl() {
        String body = """
                {
                  "kind": "Model",
                  "name": "validate-bad-url",
                  "spec": {
                    "type": "chat",
                    "upstreams": [
                      {"endpoint": ":::bad", "key": "k"}
                    ]
                  }
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertFalse(parsed.get("valid").asBoolean(), () -> "Expected valid=false: " + response.body());
        assertTrue(parsed.get("errors").isArray() && !parsed.get("errors").isEmpty(),
                () -> "Expected non-empty errors: " + response.body());
    }

    @Test
    void testT5NonModelKind() {
        String body = """
                {
                  "kind": "Role",
                  "name": "validate-role",
                  "spec": {}
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin"), 400);
    }

    @Test
    void testT6MissingKind() {
        String body = """
                {
                  "name": "validate-no-kind",
                  "spec": {}
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin"), 400);
    }

    @Test
    void testT7MissingName() {
        String body = """
                {
                  "kind": "Model",
                  "spec": {}
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin"), 400);
    }

    @Test
    void testT8MissingSpec() {
        String body = """
                {
                  "kind": "Model",
                  "name": "validate-no-spec"
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin"), 400);
    }

    @Test
    void testT9NonAdmin() {
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, VALID_MODEL_SPEC,
                "authorization", "user"), 403);
    }

    @Test
    void testT10ValidateDoesNotPersist() {
        String body = """
                {
                  "kind": "Model",
                  "name": "validate-only-model",
                  "spec": {
                    "type": "chat",
                    "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
                  }
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body,
                "authorization", "admin");
        verify(response, 200);

        Response get = send(HttpMethod.GET, "/v1/models/public/validate-only-model", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    @SneakyThrows
    void testT11SentinelInUpstreamKey() {
        String body = """
                {
                  "kind": "Model",
                  "name": "validate-sentinel",
                  "spec": {
                    "type": "chat",
                    "upstreams": [
                      {"endpoint": "http://localhost:7001", "key": "***"}
                    ]
                  }
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertFalse(parsed.get("valid").asBoolean(), () -> "Expected valid=false: " + response.body());
        assertTrue(parsed.get("errors").isArray() && !parsed.get("errors").isEmpty(),
                () -> "Expected non-empty errors: " + response.body());
    }

    @Test
    void testT12SpecNotAnObject() {
        String body = """
                {
                  "kind": "Model",
                  "name": "validate-bad-spec",
                  "spec": "a string"
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin"), 400);
    }

    @Test
    void testT13BlankName() {
        String body = """
                {
                  "kind": "Model",
                  "name": "",
                  "spec": {"type": "chat", "endpoint": "http://localhost/chat"}
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin"), 400);
    }

    @Test
    @SneakyThrows
    void testT14SentinelInUpstreamExtraData() {
        String body = """
                {
                  "kind": "Model",
                  "name": "validate-sentinel-extradata",
                  "spec": {
                    "type": "chat",
                    "upstreams": [
                      {"endpoint": "http://localhost:7001", "key": "k", "extraData": "***"}
                    ]
                  }
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertFalse(parsed.get("valid").asBoolean(), () -> "Expected valid=false: " + response.body());
        assertTrue(parsed.get("errors").isArray() && !parsed.get("errors").isEmpty(),
                () -> "Expected non-empty errors: " + response.body());
    }

    private void waitForGet(String url) {
        waitFor(() -> {
            Response r = send(HttpMethod.GET, url, null, "", "authorization", "admin");
            return r.status() == 200;
        });
    }

    private static void waitFor(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
        assertEquals(true, condition.getAsBoolean(), "Condition not met within timeout");
    }
}
