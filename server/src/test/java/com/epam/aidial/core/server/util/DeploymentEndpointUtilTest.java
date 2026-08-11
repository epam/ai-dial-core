package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.epam.aidial.core.config.InterfaceType.ANTHROPIC_MESSAGES;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_EMBEDDINGS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_RESPONSES;
import static com.epam.aidial.core.server.util.DeploymentEndpointUtil.declaresInterface;
import static com.epam.aidial.core.server.util.DeploymentEndpointUtil.requestUri;
import static com.epam.aidial.core.server.util.DeploymentEndpointUtil.responsesBaseUri;
import static com.epam.aidial.core.server.util.DeploymentEndpointUtil.servingEndpoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentEndpointUtilTest {

    @Test
    void legacyOnlyChatCompletions() {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");

        assertEquals("http://host/chat/completions", servingEndpoint(model, OPENAI_CHAT_COMPLETIONS));
        assertTrue(declaresInterface(model, OPENAI_CHAT_COMPLETIONS));

        assertNull(servingEndpoint(model, OPENAI_RESPONSES));
        assertFalse(declaresInterface(model, OPENAI_RESPONSES));
    }

    @Test
    void legacyOnlyResponses() {
        Model model = new Model();
        model.setResponsesEndpoint("http://host/openai/v1/responses");

        assertEquals("http://host/openai/v1/responses", servingEndpoint(model, OPENAI_RESPONSES));
        assertTrue(declaresInterface(model, OPENAI_RESPONSES));
        assertFalse(declaresInterface(model, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void interfacesStripTrailingSlash() {
        Model model = new Model();
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter:5000/")));

        assertEquals("http://adapter:5000", servingEndpoint(model, OPENAI_CHAT_COMPLETIONS));
        assertTrue(declaresInterface(model, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void interfacesWinWhenBothDeclared() {
        Model model = new Model();
        model.setResponsesEndpoint("http://legacy/openai/v1/responses");
        model.setInterfaces(Map.of(
                OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));

        assertEquals("http://adapter", servingEndpoint(model, OPENAI_RESPONSES));
    }

    @Test
    void neitherDeclared() {
        Model model = new Model();

        assertNull(servingEndpoint(model, OPENAI_CHAT_COMPLETIONS));
        assertNull(servingEndpoint(model, OPENAI_RESPONSES));
        assertNull(servingEndpoint(model, OPENAI_EMBEDDINGS));
        assertNull(servingEndpoint(model, ANTHROPIC_MESSAGES));
        assertFalse(declaresInterface(model, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void embeddingsDeclaredExplicitly() {
        Model model = new Model();
        model.setType(ModelType.EMBEDDING);
        model.setInterfaces(Map.of(
                OPENAI_EMBEDDINGS.getValue(), new DeploymentInterface("http://adapter:5000/")));

        assertEquals("http://adapter:5000", servingEndpoint(model, OPENAI_EMBEDDINGS));
        assertTrue(declaresInterface(model, OPENAI_EMBEDDINGS));

        // declaring embeddings alone does not make it a chat-completions deployment
        assertNull(servingEndpoint(model, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void embeddingsNotServedByChatCompletionsInterface() {
        Model model = new Model();
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter:5000")));

        // the typed map is strict: a chat-only declaration does not serve /embeddings
        assertNull(servingEndpoint(model, OPENAI_EMBEDDINGS));
        assertFalse(declaresInterface(model, OPENAI_EMBEDDINGS));
    }

    @Test
    void legacyEndpointServesEmbeddingsAndDeclaresThemForAnEmbeddingModel() {
        Model model = new Model();
        model.setType(ModelType.EMBEDDING);
        model.setEndpoint("http://host/openai/deployments/ada/embeddings");

        assertEquals("http://host/openai/deployments/ada/embeddings", servingEndpoint(model, OPENAI_EMBEDDINGS));
        assertTrue(declaresInterface(model, OPENAI_EMBEDDINGS));
        // an embedding model is not a chat-completions one, whatever its single endpoint also serves
        assertFalse(declaresInterface(model, OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://host/openai/deployments/ada/embeddings", servingEndpoint(model, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void legacyEndpointDeclaresChatCompletionsForEveryOtherModel() {
        Model chat = new Model();
        chat.setType(ModelType.CHAT);
        chat.setEndpoint("http://host/chat/completions");

        Model completion = new Model();
        completion.setType(ModelType.COMPLETION);
        completion.setEndpoint("http://host/completions");

        for (Model model : new Model[] {chat, completion}) {
            assertTrue(declaresInterface(model, OPENAI_CHAT_COMPLETIONS));
            assertFalse(declaresInterface(model, OPENAI_EMBEDDINGS));
            // the untyped endpoint still serves the whole deployments-POST family
            assertEquals(model.getEndpoint(), servingEndpoint(model, OPENAI_EMBEDDINGS));
        }
    }

    @Test
    void applicationsAndInterceptorsDeclareChatCompletionsFromTheLegacyEndpoint() {
        Application application = new Application();
        application.setEndpoint("http://host/chat/completions");
        Interceptor interceptor = new Interceptor();
        interceptor.setEndpoint("http://host/chat/completions");

        assertTrue(declaresInterface(application, OPENAI_CHAT_COMPLETIONS));
        assertFalse(declaresInterface(application, OPENAI_EMBEDDINGS));
        assertTrue(declaresInterface(interceptor, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void embeddingsPreferLegacyEndpointOverChatInterface() {
        Model model = new Model();
        model.setEndpoint("http://host/openai/deployments/ada/embeddings");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://chat-adapter")));

        // the chat-completions interface never serves embeddings; the untyped legacy endpoint does
        assertEquals("http://chat-adapter", servingEndpoint(model, OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://host/openai/deployments/ada/embeddings", servingEndpoint(model, OPENAI_EMBEDDINGS));
    }

    @Test
    void legacyEndpointServesEmbeddingsButNotResponses() {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");

        assertEquals("http://host/chat/completions", servingEndpoint(model, OPENAI_EMBEDDINGS));
        assertNull(servingEndpoint(model, OPENAI_RESPONSES));
    }

    @Test
    void embeddingsAndChatInterfacesRouteToTheirOwnAdapters() {
        Model model = new Model();
        model.setName("embedding-ada");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://chat-adapter"),
                OPENAI_EMBEDDINGS.getValue(), new DeploymentInterface("http://embeddings-adapter")));

        assertEquals("http://embeddings-adapter/openai/deployments/embedding-ada/embeddings",
                requestUri(model, OPENAI_EMBEDDINGS, "/openai/deployments/embedding-ada/embeddings", null));
        assertEquals("http://chat-adapter/openai/deployments/embedding-ada/chat/completions",
                requestUri(model, OPENAI_CHAT_COMPLETIONS, "/openai/deployments/embedding-ada/chat/completions", null));
    }

    @Test
    void responsesBaseUriAppendsThePathOnlyInTheInterfacesFlow() {
        Model interfaced = new Model();
        interfaced.setInterfaces(Map.of(
                OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter/")));
        assertEquals("http://adapter/openai/v1/responses", responsesBaseUri(interfaced));

        Model legacy = new Model();
        legacy.setResponsesEndpoint("http://legacy/custom/responses");
        // a pre-interfaces endpoint is a complete url, so nothing is appended to it
        assertEquals("http://legacy/custom/responses", responsesBaseUri(legacy));
    }

    @Test
    void requestUriAppendsTheIngressPathToTheBaseUrl() {
        Model model = new Model();
        model.setName("als-2");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://localhost:6001/")));

        assertEquals("http://localhost:6001/openai/deployments/als-2/chat/completions",
                requestUri(model, OPENAI_CHAT_COMPLETIONS, "/openai/deployments/als-2/chat/completions", null));
    }

    @Test
    void requestUriRewritesInterceptorPseudoDeploymentSegment() {
        // an interceptor calls back into Core with the literal pseudo id "interceptor" in the path
        Model model = new Model();
        model.setName("essay-assistant-gpt");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://localhost:5025")));

        assertEquals("http://localhost:5025/openai/deployments/essay-assistant-gpt/chat/completions?api-version=2024-08-06",
                requestUri(model, OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/interceptor/chat/completions", "api-version=2024-08-06"));
    }

    @Test
    void requestUriRewritesMultiSegmentPlatformBucketId() {
        // platform-bucket entities are addressed by a multi-segment canonical id (models/platform/{name});
        // the whole id must collapse to the deployment's own name, not just its first path segment
        Model model = new Model();
        model.setName("gemini-3.1-pro-preview");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://dial-vertexai.dial.svc.cluster.local")));

        assertEquals("http://dial-vertexai.dial.svc.cluster.local/openai/deployments/gemini-3.1-pro-preview/chat/completions?api-version=2025-01-01-preview",
                requestUri(model, OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/models/platform/gemini-3.1-pro-preview/chat/completions",
                        "api-version=2025-01-01-preview"));
    }

    @Test
    void requestUriUsesOverrideNameForThePathSegment() {
        // some adapters route on the deployment-name path segment, so the url has to carry the same name
        // as the body, which EnhanceDeploymentRequestFn rewrites to overrideName
        Application application = new Application();
        application.setName("app-tst");
        application.setOverrideName("essay-assistant-gpt");
        application.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://localhost:5025")));

        assertEquals("http://localhost:5025/openai/deployments/essay-assistant-gpt/chat/completions",
                requestUri(application, OPENAI_CHAT_COMPLETIONS, "/openai/deployments/app-tst/chat/completions", null));
    }

    @Test
    void requestUriEscapesOverrideNameButNotTheDeploymentName() {
        Model overridden = new Model();
        overridden.setName("openai-gpt");
        overridden.setOverrideName("gpt 5.4 mini");
        overridden.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));

        // overrideName is plain configuration text, so it is escaped to stay inside one path segment
        assertEquals("http://adapter/openai/deployments/gpt%205.4%20mini/chat/completions",
                requestUri(overridden, OPENAI_CHAT_COMPLETIONS, "/openai/deployments/openai-gpt/chat/completions", null));

        // a name is already in url form: a custom application carries its encoded resource url as its name
        Application application = new Application();
        application.setName("applications/bucket/My%20App");
        application.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));

        assertEquals("http://adapter/openai/deployments/applications/bucket/My%20App/chat/completions",
                requestUri(application, OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/applications%2Fbucket%2FMy%20App/chat/completions", null));
    }

    @Test
    void requestUriLeavesTheLegacyEndpointVerbatim() {
        // the pre-interfaces flow is a complete url: no path-segment rewriting, so overrideName cannot
        // reach it either
        Model model = new Model();
        model.setName("openai-gpt-5.4-mini");
        model.setOverrideName("gpt-5.4-mini");
        model.setEndpoint("http://localhost:6001/openai/deployments/gpt-5.4-mini/chat/completions");

        assertEquals("http://localhost:6001/openai/deployments/gpt-5.4-mini/chat/completions?api-version=2025-01-01-preview",
                requestUri(model, OPENAI_CHAT_COMPLETIONS,
                        "/openai/deployments/openai-gpt-5.4-mini/chat/completions", "api-version=2025-01-01-preview"));
    }

    @Test
    void requestUriLeavesPathWithoutDeploymentSegmentUnchanged() {
        Model model = new Model();
        model.setName("my-model");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));

        assertEquals("http://adapter/some/other/path",
                requestUri(model, OPENAI_CHAT_COMPLETIONS, "/some/other/path", null));
    }
}
