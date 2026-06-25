package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InterfaceMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testAuthority() {
        assertEquals("http://adapter:5000",
                InterfaceMigration.authority("http://adapter:5000/openai/deployments/10k/chat/completions")
        );
        assertEquals("http://localhost:4848",
                InterfaceMigration.authority("http://localhost:4848/openai/v1/responses")
        );
        assertEquals("https://host", InterfaceMigration.authority("https://host"));
        assertNull(InterfaceMigration.authority(null));
        // malformed -> raw fallback, never throws
        assertEquals("not a url", InterfaceMigration.authority("not a url"));
    }

    @Test
    public void testMigrateDeploymentLegacy() {
        Model model = new Model();
        model.setEndpoint("http://adapter:5000/openai/deployments/10k/chat/completions");
        model.setResponsesEndpoint("http://adapter:5000/openai/v1/responses");

        assertTrue(InterfaceMigration.migrateDeployment(model));
        assertEquals("http://adapter:5000", model.getInterfaceBaseUrl(InterfaceType.OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://adapter:5000", model.getInterfaceBaseUrl(InterfaceType.OPENAI_RESPONSES));
        // idempotent
        assertFalse(InterfaceMigration.migrateDeployment(model));
    }

    @Test
    public void testMigrateEmbeddingIntoChatCompletions() {
        Model model = new Model();
        model.setEndpoint("http://adapter:5000/openai/deployments/ada/embeddings");
        assertTrue(InterfaceMigration.migrateDeployment(model));
        assertTrue(model.supportsInterface(InterfaceType.OPENAI_CHAT_COMPLETIONS));
        assertFalse(model.supportsInterface(InterfaceType.OPENAI_RESPONSES));
    }

    @Test
    public void testNativeInterfacesWin() {
        Model model = new Model();
        model.setEndpoint("http://legacy:1/x");
        model.setInterfaces(
                Map.of(
                        InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(),
                        new DeploymentInterface("http://native:9999")
                )
        );

        assertFalse(InterfaceMigration.migrateDeployment(model));
        assertEquals("http://native:9999", model.getInterfaceBaseUrl(InterfaceType.OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    public void testNoLegacyNoChange() {
        Model model = new Model();
        assertFalse(InterfaceMigration.migrateDeployment(model));
        assertTrue(model.getInterfaces().isEmpty());
    }

    @Test
    public void testMigrateRawTree() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode model = root.putObject("models").putObject("gpt-3-turbo");
        model.put("type", "chat");
        model.put("endpoint", "http://localhost:4848/chat/completions");
        model.put("responsesEndpoint", "http://localhost:4848/openai/v1/responses");
        ObjectNode app = root.putObject("applications").putObject("app");
        app.put("endpoint", "http://localhost:7001/openai/deployments/10k/chat/completions");

        assertTrue(InterfaceMigration.migrateRawTree(root));

        JsonNode migratedModel = root.path("models").path("gpt-3-turbo");
        assertFalse(migratedModel.has("endpoint"));
        assertFalse(migratedModel.has("responsesEndpoint"));
        assertEquals("http://localhost:4848",
                migratedModel.path("interfaces").path("openaiChatCompletions").path("base_url").asText());
        assertEquals("http://localhost:4848",
                migratedModel.path("interfaces").path("openaiResponses").path("base_url").asText());
        // other fields preserved
        assertEquals("chat", migratedModel.path("type").asText());

        assertEquals("http://localhost:7001",
                root.path("applications").path("app").path("interfaces")
                        .path("openaiChatCompletions").path("base_url").asText());

        // idempotent
        assertFalse(InterfaceMigration.migrateRawTree(root));
    }

    @Test
    public void testHasLegacyEndpoints() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode model = root.putObject("models").putObject("gpt-3-turbo");
        model.put("type", "chat");
        model.put("endpoint", "http://localhost:4848/chat/completions");

        assertTrue(InterfaceMigration.hasLegacyEndpoints(root));
        // non-mutating: legacy fields are left in place for the in-memory (Layer A) path
        assertTrue(root.path("models").path("gpt-3-turbo").has("endpoint"));
        assertFalse(root.path("models").path("gpt-3-turbo").has("interfaces"));
    }

    @Test
    public void testHasLegacyEndpointsInApplications() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("applications")
                .putObject("app")
                .put("responsesEndpoint", "http://localhost:7001/openai/v1/responses");

        assertTrue(InterfaceMigration.hasLegacyEndpoints(root));
    }

    @Test
    public void testHasLegacyEndpointsNativeInterfacesWithStaleLegacy() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode model = root.putObject("models").putObject("native");
        model.put("endpoint", "http://legacy:1/x");
        model.putObject("interfaces").putObject("openaiChatCompletions").put("base_url", "http://native:9999");

        // the obsolete legacy field is still detected (write-back strips it even though interfaces win)
        assertTrue(InterfaceMigration.hasLegacyEndpoints(root));
    }

    @Test
    public void testHasLegacyEndpointsEmpty() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("models")
                .putObject("native")
                .putObject("interfaces")
                .putObject("openaiResponses")
                .put("base_url", "http://native:9999");

        assertFalse(InterfaceMigration.hasLegacyEndpoints(root));
        assertFalse(InterfaceMigration.hasLegacyEndpoints(MAPPER.createObjectNode()));
        assertFalse(InterfaceMigration.hasLegacyEndpoints(null));
    }

    @Test
    public void testMigrateInterceptorUsesAuthority() {
        // Interceptors are routed exactly like models/applications: authority only.
        Interceptor interceptor = new Interceptor();
        interceptor.setEndpoint("http://localhost:4088/api/v1/interceptor/handle");

        assertTrue(InterfaceMigration.migrateDeployment(interceptor));
        assertEquals("http://localhost:4088",
                interceptor.getInterfaceBaseUrl(InterfaceType.OPENAI_CHAT_COMPLETIONS));
        // interceptors carry no Responses endpoint, so no Responses interface
        assertFalse(interceptor.supportsInterface(InterfaceType.OPENAI_RESPONSES));
        // idempotent
        assertFalse(InterfaceMigration.migrateDeployment(interceptor));
    }

    @Test
    public void testMigrateRawTreeInterceptorsSection() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode interceptor = root.putObject("interceptors").putObject("guard");
        interceptor.put("endpoint", "http://localhost:4088/api/v1/interceptor/handle");

        assertTrue(InterfaceMigration.migrateRawTree(root));

        JsonNode migrated = root.path("interceptors").path("guard");
        assertFalse(migrated.has("endpoint"));
        // authority only, same as models/applications
        assertEquals("http://localhost:4088",
                migrated.path("interfaces").path("openaiChatCompletions").path("base_url").asText());
        assertTrue(migrated.path("interfaces").path("openaiResponses").isMissingNode());

        // idempotent
        assertFalse(InterfaceMigration.migrateRawTree(root));
    }

    @Test
    public void testHasLegacyEndpointsInInterceptors() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("interceptors")
                .putObject("guard")
                .put("endpoint", "http://localhost:4088/api/v1/interceptor/handle");

        assertTrue(InterfaceMigration.hasLegacyEndpoints(root));
    }

    @Test
    public void testMigrateRawTreeStripsLegacyKeepsNativeInterfaces() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode model = root.putObject("models").putObject("native");
        model.put("endpoint", "http://legacy:1/x");
        model.put("responsesEndpoint", "http://legacy:2/y");
        model.putObject("interfaces")
                .putObject("openaiChatCompletions")
                .put("base_url", "http://native:9999");

        // the redundant legacy fields are stripped, native interfaces are preserved (not overwritten)
        assertTrue(InterfaceMigration.migrateRawTree(root));
        assertFalse(model.has("endpoint"));
        assertFalse(model.has("responsesEndpoint"));
        assertEquals("http://native:9999",
                model.path("interfaces").path("openaiChatCompletions").path("base_url").asText()
        );
        // native interfaces are not augmented from the dropped legacy responsesEndpoint
        assertTrue(model.path("interfaces").path("openaiResponses").isMissingNode());

        // idempotent
        assertFalse(InterfaceMigration.migrateRawTree(root));
    }
}
