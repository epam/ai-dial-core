package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_EMBEDDINGS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_RESPONSES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void legacyOnlyChatCompletions() {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");

        assertTrue(model.supportsInterface(OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://host/chat/completions", model.resolveEndpoint(OPENAI_CHAT_COMPLETIONS));

        assertFalse(model.supportsInterface(OPENAI_RESPONSES));
        assertNull(model.resolveEndpoint(OPENAI_RESPONSES));
    }

    @Test
    void legacyOnlyResponses() {
        Model model = new Model();
        model.setResponsesEndpoint("http://host/openai/v1/responses");

        assertTrue(model.supportsInterface(OPENAI_RESPONSES));
        assertEquals("http://host/openai/v1/responses", model.resolveEndpoint(OPENAI_RESPONSES));

        assertFalse(model.supportsInterface(OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void interfacesOnlyStripsTrailingSlash() {
        Model model = new Model();
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter:5000/")));

        assertTrue(model.supportsInterface(OPENAI_CHAT_COMPLETIONS));
        assertNull(model.getEndpoint());
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
        assertEquals("http://legacy/openai/v1/responses", model.getResponsesEndpoint());
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
    void embeddingsDeclaredExplicitly() {
        Model model = new Model();
        model.setType(ModelType.EMBEDDING);
        model.setInterfaces(Map.of(
                OPENAI_EMBEDDINGS.getValue(), new DeploymentInterface("http://adapter:5000/")));

        assertTrue(model.supportsInterface(OPENAI_EMBEDDINGS));
        assertEquals("http://adapter:5000", model.resolveEndpoint(OPENAI_EMBEDDINGS));

        // declaring embeddings alone does not make the deployment a chat-completions one: the fallback
        // is one-way, so a chat-completions request finds no configuration to serve it
        assertNull(model.resolveEndpoint(OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void embeddingsFallsBackToChatCompletionsInterface() {
        Model model = new Model();
        model.setName("embedding-ada");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter:5000")));

        // the type is not declared, so it is not advertised...
        assertFalse(model.supportsInterface(OPENAI_EMBEDDINGS));
        // ...but the chat-completions entry keeps serving embeddings as it did before the split
        assertEquals("http://adapter:5000", model.resolveEndpoint(OPENAI_EMBEDDINGS));
        assertEquals("http://adapter:5000/openai/deployments/embedding-ada/embeddings",
                model.resolveRequestUri(OPENAI_EMBEDDINGS, "/openai/deployments/embedding-ada/embeddings", null));
    }

    @Test
    void embeddingsFallsBackToLegacyEndpoint() {
        Model model = new Model();
        model.setType(ModelType.EMBEDDING);
        model.setEndpoint("http://host/openai/deployments/ada/embeddings");

        // the untyped legacy endpoint declares chat completions only, yet it is what serves embeddings
        assertFalse(model.supportsInterface(OPENAI_EMBEDDINGS));
        assertEquals("http://host/openai/deployments/ada/embeddings", model.resolveEndpoint(OPENAI_EMBEDDINGS));
        assertEquals("http://host/openai/deployments/ada/embeddings",
                model.resolveRequestUri(OPENAI_EMBEDDINGS, "/openai/deployments/embedding-ada/embeddings", null));
    }

    @Test
    void embeddingsNotServedWhenNothingDeclared() {
        Model model = new Model();
        model.setResponsesEndpoint("http://host/openai/v1/responses");

        assertNull(model.resolveEndpoint(OPENAI_EMBEDDINGS));
    }

    @Test
    void embeddingsPrefersOwnInterfaceOverChatCompletions() {
        Model model = new Model();
        model.setName("embedding-ada");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://chat-adapter"),
                OPENAI_EMBEDDINGS.getValue(), new DeploymentInterface("http://embeddings-adapter")));

        assertEquals("http://embeddings-adapter", model.resolveEndpoint(OPENAI_EMBEDDINGS));
        assertEquals("http://embeddings-adapter/openai/deployments/embedding-ada/embeddings",
                model.resolveRequestUri(OPENAI_EMBEDDINGS, "/openai/deployments/embedding-ada/embeddings", null));
        assertEquals("http://chat-adapter/openai/deployments/embedding-ada/chat/completions",
                model.resolveRequestUri(OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/embedding-ada/chat/completions", null));
    }

    @Test
    void fallbackAppliesToEmbeddingsOnly() {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");

        // embeddings is the only type a chat-completions endpoint stands in for: the Responses API
        // keeps requiring its own configuration
        assertNull(model.resolveEndpoint(OPENAI_RESPONSES));
    }

    @Test
    void resolveUriAppendsPathInNewFlowAndIgnoresItInLegacyFlow() {
        Model interfaced = new Model();
        interfaced.setInterfaces(Map.of(
                OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter/")));
        assertEquals("http://adapter/openai/v1/responses",
                interfaced.resolveUri(OPENAI_RESPONSES, "/openai/v1/responses"));

        Model legacy = new Model();
        legacy.setResponsesEndpoint("http://legacy/custom/responses");
        // the legacy endpoint already encodes the path, so the passed path is ignored
        assertEquals("http://legacy/custom/responses",
                legacy.resolveUri(OPENAI_RESPONSES, "/openai/v1/responses"));
    }

    @Test
    void resolveRequestUriAppendsIngressPathToBaseUrl() {
        Model model = new Model();
        model.setName("als-2");
        model.setInterfaces(Map.of(
                OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://localhost:6001/")));

        assertEquals("http://localhost:6001/openai/v1/responses",
                model.resolveRequestUri(OPENAI_RESPONSES, "/openai/v1/responses", null));
    }

    @Test
    void resolveRequestUriRewritesInterceptorPseudoDeploymentSegment() {
        // When a deployment has interceptors configured, the interceptor calls back into Core using the
        // literal pseudo deployment id "interceptor" in the path instead of the real deployment name.
        Model model = new Model();
        model.setName("essay-assistant-gpt");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://localhost:5025")));

        assertEquals("http://localhost:5025/openai/deployments/essay-assistant-gpt/chat/completions?api-version=2024-08-06",
                model.resolveRequestUri(OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/interceptor/chat/completions", "api-version=2024-08-06"));
    }

    @Test
    void resolveRequestUriRewritesMultiSegmentPlatformBucketId() {
        // Platform-bucket entities are addressed by a multi-segment canonical id (models/platform/{name});
        // the whole id must collapse to the deployment's own name, not just its first path segment.
        Model model = new Model();
        model.setName("gemini-3.1-pro-preview");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://dial-vertexai.dial.svc.cluster.local")));

        assertEquals("http://dial-vertexai.dial.svc.cluster.local/openai/deployments/gemini-3.1-pro-preview/chat/completions?api-version=2025-01-01-preview",
                model.resolveRequestUri(OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/models/platform/gemini-3.1-pro-preview/chat/completions?api-version=2025-01-01-preview",
                        "api-version=2025-01-01-preview"));
    }

    @Test
    void resolveRequestUriUsesOverrideNameForPathSegmentWhenSet() {
        // overrideName rewrites the model field/header, but the outgoing URL must match too: some
        // adapters route on the deployment-name path segment (e.g. an Azure-style FastAPI app registering
        // routes per deployment name), so the URL has to carry the same name as the body.
        Application application = new Application();
        application.setName("app-tst");
        application.setOverrideName("essay-assistant-gpt");
        application.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://localhost:5025")));

        assertEquals("http://localhost:5025/openai/deployments/essay-assistant-gpt/chat/completions?api-version=2024-08-06",
                application.resolveRequestUri(OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/app-tst/chat/completions", "api-version=2024-08-06"));
    }

    @Test
    void resolveRequestUriUsesOverrideNameForPathSegmentWhenSet_Model() {
        // Same scenario as resolveRequestUriUsesOverrideNameForPathSegmentWhenSet, but for a Model:
        // the interfaces/base_url flow rewrites the chat-completions URL's deployment-name segment to
        // overrideName, matching what EnhanceDeploymentRequestFn already does to the body's model field.
        Model model = new Model();
        model.setName("openai-gpt-5.4-mini");
        model.setOverrideName("gpt-5.4-mini");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://localhost:6001")));

        assertEquals("http://localhost:6001/openai/deployments/gpt-5.4-mini/chat/completions?api-version=2025-01-01-preview",
                model.resolveRequestUri(OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/openai-gpt-5.4-mini/chat/completions",
                        "api-version=2025-01-01-preview"));
    }

    @Test
    void resolveRequestUriLegacyEndpointIgnoresOverrideName() {
        // The legacy `endpoint` flow must behave exactly as before this change: it's a verbatim,
        // fully-qualified URL with no deployment-name path-segment rewriting, so overrideName has no
        // effect on it even when set.
        Model model = new Model();
        model.setName("openai-gpt-5.4-mini");
        model.setOverrideName("gpt-5.4-mini");
        model.setEndpoint("http://localhost:6001/openai/deployments/gpt-5.4-mini/chat/completions");

        assertEquals("http://localhost:6001/openai/deployments/gpt-5.4-mini/chat/completions?api-version=2025-01-01-preview",
                model.resolveRequestUri(OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/openai-gpt-5.4-mini/chat/completions",
                        "api-version=2025-01-01-preview"));
    }

    @Test
    void resolveRequestUriFallsBackToLegacyEndpointWithQuery() {
        Model model = new Model();
        model.setName("als-2");
        model.setEndpoint("http://legacy/openai/deployments/1/chat/completions");

        assertEquals("http://legacy/openai/deployments/1/chat/completions?api-version=2024-10-21",
                model.resolveRequestUri(OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/als-2/chat/completions", "api-version=2024-10-21"));
        assertEquals("http://legacy/openai/deployments/1/chat/completions",
                model.resolveRequestUri(OPENAI_CHAT_COMPLETIONS, "/openai/deployments/als-2/chat/completions", null));
    }

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

        assertTrue(model.supportsInterface(OPENAI_CHAT_COMPLETIONS));
        assertEquals(2, model.getInterfaces().size());
        // unknown keys parse but never resolve to a routed interface type
        assertNull(model.resolveEndpoint(OPENAI_RESPONSES));
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
    void overrideNameAvailableOnApplicationAndInterceptor() {
        Application application = new Application();
        application.setOverrideName("app-override");
        assertEquals("app-override", application.getOverrideName());

        Interceptor interceptor = new Interceptor();
        interceptor.setOverrideName("interceptor-override");
        assertEquals("interceptor-override", interceptor.getOverrideName());
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

        String json = MAPPER.writeValueAsString(model);
        Model restored = MAPPER.readValue(json, Model.class);

        // legacy field is not stripped, interfaces survive
        assertEquals("http://host/chat/completions", restored.getEndpoint());
        assertEquals("http://adapter", restored.resolveEndpoint(OPENAI_RESPONSES));
    }
}
