package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ApplicationTypeSchemaApiTest extends ResourceBaseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testApplicationTypeSchemaList_ok() {
        Response response = send(HttpMethod.GET, "/v1/application_type_schemas/schemas", null, null);

        Assertions.assertEquals(200, response.status());
        AtomicReference<JsonNode> jsonNode = new AtomicReference<>();
        Assertions.assertDoesNotThrow(() -> jsonNode.set(objectMapper.readTree(response.body())));
        Assertions.assertTrue(jsonNode.get().isArray());
        Assertions.assertFalse(jsonNode.get().isEmpty());
        jsonNode.get().forEach(node -> {
            Assertions.assertTrue(node.has("$id"));
            Assertions.assertTrue(node.has("dial:applicationTypeEditorUrl"));
            Assertions.assertTrue(node.has("dial:applicationTypeDisplayName"));
            Assertions.assertTrue(node.has("dial:applicationTypeViewerUrl"));
        });

        JsonNode specificType = null;
        for (JsonNode node : jsonNode.get()) {
            if (node.get("$id").asText().endsWith("/specific_application_type")) {
                specificType = node;
                break;
            }
        }
        Assertions.assertNotNull(specificType, "specific_application_type schema must be present");
        JsonNode routes = specificType.get("dial:applicationTypeRoutes");
        Assertions.assertNotNull(routes, "dial:applicationTypeRoutes must be exposed in the schema list");
        Assertions.assertTrue(routes.has("data_sync"));
    }

    @Test
    void testApplicationTypeSchemaMetaSchema_ok() {
        Response response = send(HttpMethod.GET, "/v1/application_type_schemas/meta_schema", null, null);

        Assertions.assertEquals(200, response.status());
        AtomicReference<JsonNode> jsonNodeRef = new AtomicReference<>();
        Assertions.assertDoesNotThrow(() -> jsonNodeRef.set(objectMapper.readTree(response.body())));
        JsonNode node = jsonNodeRef.get();
        Assertions.assertTrue(node.isObject());
        Assertions.assertTrue(node.has("$id"));
        Assertions.assertTrue(node.has("$schema"));
    }

    @Test
    void testApplicationTypeSchemaSchema_ok() {
        Response response = send(HttpMethod.GET, "/v1/application_type_schemas/schema",
                "id=https://mydial.somewhere.com/custom_application_schemas/specific_application_type", null);

        Assertions.assertEquals(200, response.status());
        AtomicReference<JsonNode> jsonNodeRef = new AtomicReference<>();
        Assertions.assertDoesNotThrow(() -> jsonNodeRef.set(objectMapper.readTree(response.body())));
        JsonNode node = jsonNodeRef.get();
        Assertions.assertTrue(node.isObject());
        Assertions.assertTrue(node.has("$id"));
        Assertions.assertTrue(node.has("$schema"));
        Assertions.assertTrue(node.has("dial:applicationTypeViewerUrl"));
    }

    @Test
    void testGetSchemaBySchemaEndpoint() throws JsonProcessingException {
        String responseBody = """
                {
                 "properties": {
                    "property1": {
                        "title": "Property 1",
                        "type": "string",
                        "dial:meta": {
                            "dial:propertyKind": "client",
                            "dial:propertyOrder": 1
                        }
                    }
                 },
                 "required": ["property1"]
                }""";
        try (TestWebServer server = new TestWebServer(4848)) {
            TestWebServer.Handler handler = request -> {
                MockResponse response = new MockResponse();
                response.setResponseCode(200);
                response.setBody(responseBody);
                return response;
            };
            server.map(HttpMethod.GET, "/schema", handler);

            ResourceBaseTest.Response response = send(HttpMethod.GET, "/v1/application_type_schemas/schema",
                    "id=https://mydial.somewhere.com/custom_application_schemas/schema_endpoint", null);

            verify(response, 200);
            JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
            assertNotNull(body.get("properties"));
        }
    }

}
