package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.epam.aidial.core.config.InterfaceType.ANTHROPIC_MESSAGES;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_RESPONSES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State and serialization of a deployment. Which endpoint serves an interface type, and which types are
 * advertised, belong to {@code DeploymentEndpointUtil} and are covered by its own test.
 */
public class DeploymentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void interfaceParsesWithoutBaseUrl() throws Exception {
        String json = """
                {
                    "baseUrl": "http://adapter",
                    "interfaces": {
                        "openaiChatCompletions": {"mode": "passthrough"},
                        "anthropicMessages": {"mode": "translator", "base_url": "http://translator"}
                    }
                }
                """;
        Model model = MAPPER.readValue(json, Model.class);

        assertEquals("http://adapter", model.getBaseUrl());
        // the deployment-level base url is not copied into the entry: it is resolved per request
        assertNull(model.getInterfaces().get(OPENAI_CHAT_COMPLETIONS.getValue()).getBaseUrl());
        assertEquals(InterfaceMode.PASSTHROUGH, model.getInterfaces().get(OPENAI_CHAT_COMPLETIONS.getValue()).getMode());
        assertEquals("http://translator", model.getInterfaces().get(ANTHROPIC_MESSAGES.getValue()).getBaseUrl());
        assertEquals(InterfaceMode.TRANSLATOR, model.getInterfaces().get(ANTHROPIC_MESSAGES.getValue()).getMode());
    }

    @Test
    void interfaceMappedToNullParses() throws Exception {
        String json = """
                {
                    "baseUrl": "http://adapter",
                    "interfaces": {
                        "openaiChatCompletions": {},
                        "openaiResponses": null
                    }
                }
                """;
        Model model = MAPPER.readValue(json, Model.class);

        // an explicit null declares the interface unsupported, exactly as leaving it out does
        assertTrue(model.getInterfaces().containsKey(OPENAI_RESPONSES.getValue()));
        assertNull(model.getInterfaces().get(OPENAI_RESPONSES.getValue()));
        assertNull(model.getInterfaces().get(OPENAI_CHAT_COMPLETIONS.getValue()).getMode());
    }

    @Test
    void modeIsOmittedWhenAbsentAndBaseUrlKeepsItsSnakeCaseName() throws Exception {
        Model model = new Model();
        model.setBaseUrl("http://adapter");
        model.setInterfaces(Map.of(OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://responses-adapter")));

        String json = MAPPER.writeValueAsString(model);

        assertTrue(json.contains("\"base_url\":\"http://responses-adapter\""), json);
        assertTrue(json.contains("\"baseUrl\":\"http://adapter\""), json);
        assertFalse(json.contains("\"mode\""), json);
    }

    @Test
    void baseUrlIsOmittedWhenUnset() throws Exception {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");

        assertFalse(MAPPER.writeValueAsString(model).contains("baseUrl"));
    }

    @Test
    void applicationCopyConstructorPreservesBaseUrl() {
        Application source = new Application();
        source.setName("app1");
        source.setBaseUrl("http://adapter");

        assertEquals("http://adapter", new Application(source).getBaseUrl());
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
    void defaultHeadersFallBackToDeploymentLevelForEveryInterface() {
        Model model = new Model();
        model.setDefaultHeaders(Map.of("x-dial-cache-policy", "cache-priority"));
        model.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), new DeploymentInterface("http://anthropic")));

        // declared without headers of its own, and not declared at all, resolve alike
        assertEquals(Map.of("x-dial-cache-policy", "cache-priority"), model.resolveDefaultHeaders(ANTHROPIC_MESSAGES));
        assertEquals(Map.of("x-dial-cache-policy", "cache-priority"), model.resolveDefaultHeaders(OPENAI_RESPONSES));
    }

    @Test
    void interfaceDefaultHeadersOverrideAndExtendDeploymentLevel() {
        DeploymentInterface anthropic = new DeploymentInterface("http://anthropic");
        anthropic.setDefaultHeaders(Map.of("x-dial-custom-header", "foo-bar-2", "x-dial-custom-header-2", "some-value"));
        Model model = new Model();
        model.setDefaultHeaders(Map.of("x-dial-cache-policy", "cache-priority", "x-dial-custom-header", "foo-bar"));
        model.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), anthropic));

        assertEquals(
                Map.of("x-dial-cache-policy", "cache-priority",
                        "x-dial-custom-header", "foo-bar-2",
                        "x-dial-custom-header-2", "some-value"),
                model.resolveDefaultHeaders(ANTHROPIC_MESSAGES));
        // the overlay is scoped to its own interface
        assertEquals(
                Map.of("x-dial-cache-policy", "cache-priority", "x-dial-custom-header", "foo-bar"),
                model.resolveDefaultHeaders(OPENAI_RESPONSES));
    }

    @Test
    void interfaceDefaultHeadersOverrideDeploymentLevelSpelledInAnotherCase() {
        DeploymentInterface anthropic = new DeploymentInterface("http://anthropic");
        anthropic.setDefaultHeaders(Map.of("x-dial-custom-header", "foo-bar-2"));
        Model model = new Model();
        model.setDefaultHeaders(Map.of("X-Dial-Custom-Header", "foo-bar"));
        model.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), anthropic));

        Map<String, String> resolved = model.resolveDefaultHeaders(ANTHROPIC_MESSAGES);

        assertEquals(1, resolved.size());
        assertEquals("foo-bar-2", resolved.get("X-DIAL-CUSTOM-HEADER"));
    }

    @Test
    void roundTripDefaultHeaders() throws Exception {
        String json = """
                {
                    "endpoint": "http://host/chat/completions",
                    "defaultHeaders": {"x-dial-cache-policy": "cache-priority"},
                    "interfaces": {
                        "anthropicMessages": {
                            "base_url": "http://anthropic",
                            "default_headers": {"x-dial-custom-header": "foo-bar-2"}
                        }
                    }
                }
                """;

        Model restored = MAPPER.readValue(MAPPER.writeValueAsString(MAPPER.readValue(json, Model.class)), Model.class);

        assertEquals(Map.of("x-dial-cache-policy", "cache-priority"), restored.getDefaultHeaders());
        assertEquals(
                Map.of("x-dial-cache-policy", "cache-priority", "x-dial-custom-header", "foo-bar-2"),
                restored.resolveDefaultHeaders(ANTHROPIC_MESSAGES));
    }

    @Test
    void defaultHeadersAreOmittedWhenEmpty() throws Exception {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");
        model.setInterfaces(Map.of(OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));

        String json = MAPPER.writeValueAsString(model);

        assertFalse(json.contains("defaultHeaders"), json);
    }

    @Test
    void applicationCopyConstructorPreservesDefaultHeaders() {
        Application source = new Application();
        source.setName("app1");
        source.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));
        source.setDefaultHeaders(Map.of("x-dial-cache-policy", "cache-priority"));

        Application copy = new Application(source);

        assertEquals(Map.of("x-dial-cache-policy", "cache-priority"), copy.getDefaultHeaders());
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
