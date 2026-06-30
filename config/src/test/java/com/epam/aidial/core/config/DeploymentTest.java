package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_RESPONSES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void legacyOnlyChatCompletions() {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");

        assertTrue(model.supportsInterface(OPENAI_CHAT_COMPLETIONS));
        assertNull(model.getInterfaceBaseUrl(OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://host/chat/completions", model.getLegacyEndpoint(OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://host/chat/completions", model.resolveEndpoint(OPENAI_CHAT_COMPLETIONS));

        assertFalse(model.supportsInterface(OPENAI_RESPONSES));
        assertNull(model.resolveEndpoint(OPENAI_RESPONSES));
    }

    @Test
    void legacyOnlyResponses() {
        Model model = new Model();
        model.setResponsesEndpoint("http://host/openai/v1/responses");

        assertTrue(model.supportsInterface(OPENAI_RESPONSES));
        assertNull(model.getInterfaceBaseUrl(OPENAI_RESPONSES));
        assertEquals("http://host/openai/v1/responses", model.getLegacyEndpoint(OPENAI_RESPONSES));
        assertEquals("http://host/openai/v1/responses", model.resolveEndpoint(OPENAI_RESPONSES));

        assertFalse(model.supportsInterface(OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void interfacesOnlyStripsTrailingSlash() {
        Model model = new Model();
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter:5000/")));

        assertTrue(model.supportsInterface(OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://adapter:5000", model.getInterfaceBaseUrl(OPENAI_CHAT_COMPLETIONS));
        assertNull(model.getLegacyEndpoint(OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://adapter:5000", model.resolveEndpoint(OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void interfacesWinWhenBothDeclared() {
        Model model = new Model();
        model.setResponsesEndpoint("http://legacy/openai/v1/responses");
        model.setInterfaces(Map.of(
                OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));

        assertTrue(model.supportsInterface(OPENAI_RESPONSES));
        assertEquals("http://adapter", model.resolveEndpoint(OPENAI_RESPONSES));
        // legacy field is left untouched in config
        assertEquals("http://legacy/openai/v1/responses", model.getLegacyEndpoint(OPENAI_RESPONSES));
    }

    @Test
    void neitherDeclared() {
        Model model = new Model();
        assertFalse(model.supportsInterface(OPENAI_CHAT_COMPLETIONS));
        assertFalse(model.supportsInterface(OPENAI_RESPONSES));
        assertNull(model.resolveEndpoint(OPENAI_CHAT_COMPLETIONS));
        assertNull(model.resolveEndpoint(OPENAI_RESPONSES));
    }

    @Test
    void unknownInterfaceKeysAreTolerated() throws Exception {
        String json = """
                {
                    "endpoint": "http://host/chat/completions",
                    "interfaces": {
                        "anthropicMessages": {"base_url": "http://anthropic"},
                        "geminiGenerateContent": {"base_url": "http://gemini"}
                    }
                }
                """;
        Model model = MAPPER.readValue(json, Model.class);

        assertTrue(model.supportsInterface(OPENAI_CHAT_COMPLETIONS));
        assertEquals(2, model.getInterfaces().size());
        // unknown keys parse but never resolve to a routed interface type
        assertNull(model.getInterfaceBaseUrl(OPENAI_RESPONSES));
    }

    @Test
    void applicationCopyConstructorPreservesInterfaces() {
        Application source = new Application();
        source.setName("app1");
        source.setEndpoint("http://host/chat/completions");
        source.setInterfaces(Map.of(
                OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));

        Application copy = new Application(source);

        assertEquals(source.getInterfaces(), copy.getInterfaces());
        assertEquals("http://adapter", copy.resolveEndpoint(OPENAI_RESPONSES));
    }

    @Test
    void supportedInterfaceKeysByDeploymentKind() {
        // models: no restriction (forward-compatible, tolerate unknown keys)
        assertNull(new Model().supportedInterfaceKeys());
        // applications and interceptors: only the chat completions interface
        assertEquals(java.util.Set.of(OPENAI_CHAT_COMPLETIONS.getValue()),
                new Application().supportedInterfaceKeys());
        assertEquals(java.util.Set.of(OPENAI_CHAT_COMPLETIONS.getValue()),
                new Interceptor().supportedInterfaceKeys());
    }

    @Test
    void roundTripLegacy() throws Exception {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");
        model.setResponsesEndpoint("http://host/openai/v1/responses");

        Model restored = MAPPER.readValue(MAPPER.writeValueAsString(model), Model.class);

        assertEquals("http://host/chat/completions", restored.getEndpoint());
        assertEquals("http://host/openai/v1/responses", restored.getResponsesEndpoint());
        assertTrue(restored.getInterfaces().isEmpty());
    }

    @Test
    void roundTripInterfaces() throws Exception {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");
        model.setInterfaces(Map.of(
                OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));

        String json = MAPPER.writeValueAsString(model);
        Model restored = MAPPER.readValue(json, Model.class);

        // legacy field is not stripped, interfaces survive
        assertEquals("http://host/chat/completions", restored.getEndpoint());
        assertEquals("http://adapter", restored.getInterfaceBaseUrl(OPENAI_RESPONSES));
    }
}
