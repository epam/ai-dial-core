package com.epam.aidial.core.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

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
        });
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

}
