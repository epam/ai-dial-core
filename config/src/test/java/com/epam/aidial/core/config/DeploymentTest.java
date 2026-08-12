package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.epam.aidial.core.config.InterfaceType.OPENAI_RESPONSES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State and serialization of a deployment. Which endpoint serves an interface type, and which types are
 * advertised, belong to {@code DeploymentEndpointUtil} and are covered by its own test.
 */
public class DeploymentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void baseUrlIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new DeploymentInterface(null));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentInterface(""));
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

        // unknown keys parse and are kept, they simply never match a known interface type
        assertEquals(2, model.getInterfaces().size());
        assertEquals("http://gemini", model.getInterfaces().get("geminiGenerateContent").getBaseUrl());
        assertNull(model.getInterfaces().get(OPENAI_RESPONSES.getValue()));
        assertEquals("http://host/chat/completions", model.getEndpoint());
    }

    @Test
    void targetNameIsOverrideNameWhenSet() {
        Model model = new Model();
        model.setName("openai-gpt-5.4-mini");
        assertEquals("openai-gpt-5.4-mini", model.getTargetName());

        model.setOverrideName("gpt-5.4-mini");
        assertEquals("gpt-5.4-mini", model.getTargetName());
    }

    @Test
    void overrideNameAvailableOnApplicationAndInterceptor() {
        Application application = new Application();
        application.setOverrideName("app-override");
        assertEquals("app-override", application.getOverrideName());

        Interceptor interceptor = new Interceptor();
        interceptor.setOverrideName("interceptor-override");
        assertEquals("interceptor-override", interceptor.getOverrideName());
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
        assertEquals("http://adapter", copy.getInterfaces().get(OPENAI_RESPONSES.getValue()).getBaseUrl());
    }

    @Test
    void applicationCopyConstructorPreservesOverrideName() {
        Application source = new Application();
        source.setName("app1");
        source.setOverrideName("app-override");

        Application copy = new Application(source);

        assertEquals("app-override", copy.getOverrideName());
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

        Model restored = MAPPER.readValue(MAPPER.writeValueAsString(model), Model.class);

        // legacy field is not stripped, interfaces survive
        assertEquals("http://host/chat/completions", restored.getEndpoint());
        assertEquals("http://adapter", restored.getInterfaces().get(OPENAI_RESPONSES.getValue()).getBaseUrl());
    }
}
