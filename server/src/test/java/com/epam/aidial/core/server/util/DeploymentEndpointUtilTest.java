package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.TranslatorRef;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.epam.aidial.core.config.InterfaceType.ANTHROPIC_MESSAGES;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_EMBEDDINGS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_RESPONSES;
import static com.epam.aidial.core.server.util.DeploymentEndpointUtil.isInterfaceDeclared;
import static com.epam.aidial.core.server.util.DeploymentEndpointUtil.resolveMode;
import static com.epam.aidial.core.server.util.DeploymentEndpointUtil.resolveRequestUri;
import static com.epam.aidial.core.server.util.DeploymentEndpointUtil.resolveResponsesBaseUri;
import static com.epam.aidial.core.server.util.DeploymentEndpointUtil.resolveServingEndpoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentEndpointUtilTest {

    private static final Map<String, Translator> NO_TRANSLATORS = Map.of();

    @Test
    void legacyOnlyChatCompletions() {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");

        assertEquals("http://host/chat/completions", resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
        assertTrue(isInterfaceDeclared(model, OPENAI_CHAT_COMPLETIONS));

        assertNull(resolveServingEndpoint(model, OPENAI_RESPONSES, NO_TRANSLATORS));
        assertFalse(isInterfaceDeclared(model, OPENAI_RESPONSES));
    }

    @Test
    void legacyOnlyResponses() {
        Model model = new Model();
        model.setResponsesEndpoint("http://host/openai/v1/responses");

        assertEquals("http://host/openai/v1/responses", resolveServingEndpoint(model, OPENAI_RESPONSES, NO_TRANSLATORS));
        assertTrue(isInterfaceDeclared(model, OPENAI_RESPONSES));
        assertFalse(isInterfaceDeclared(model, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void interfacesStripTrailingSlash() {
        Model model = new Model();
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter:5000/")));

        assertEquals("http://adapter:5000", resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
        assertTrue(isInterfaceDeclared(model, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void interfacesFallBackToTheDeploymentBaseUrl() {
        Model model = new Model();
        model.setBaseUrl("http://adapter:5000/");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface(),
                OPENAI_RESPONSES.getValue(), new DeploymentInterface()));

        assertEquals("http://adapter:5000", resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
        assertEquals("http://adapter:5000", resolveServingEndpoint(model, OPENAI_RESPONSES, NO_TRANSLATORS));
        assertTrue(isInterfaceDeclared(model, OPENAI_CHAT_COMPLETIONS));
        assertTrue(isInterfaceDeclared(model, OPENAI_RESPONSES));
    }

    @Test
    void interfaceBaseUrlOverridesTheDeploymentOne() {
        Model model = new Model();
        model.setBaseUrl("http://openai-adapter");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface(),
                ANTHROPIC_MESSAGES.getValue(), new DeploymentInterface("http://bedrock-adapter/")));

        assertEquals("http://openai-adapter", resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
        assertEquals("http://bedrock-adapter", resolveServingEndpoint(model, ANTHROPIC_MESSAGES, NO_TRANSLATORS));
    }

    @Test
    void deploymentBaseUrlServesOnlyDeclaredInterfaces() {
        Model model = new Model();
        model.setBaseUrl("http://adapter");
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), new DeploymentInterface()));

        // interfaces is the whitelist; a base url alone declares nothing
        assertNull(resolveServingEndpoint(model, OPENAI_RESPONSES, NO_TRANSLATORS));
        assertFalse(isInterfaceDeclared(model, OPENAI_RESPONSES));
        // an undeclared type still reads the legacy field, which the base url never stands in for
        assertEquals("http://legacy/chat/completions", resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
    }

    @Test
    void interfaceWithoutAnyBaseUrlIsNotServed() {
        Model model = new Model();
        model.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), new DeploymentInterface()));

        assertNull(resolveServingEndpoint(model, ANTHROPIC_MESSAGES, NO_TRANSLATORS));
        assertFalse(isInterfaceDeclared(model, ANTHROPIC_MESSAGES));
    }

    @Test
    void translatedInterfaceIsServedByItsTranslator() {
        Model model = new Model();
        model.setName("gpt-5.5");
        model.setBaseUrl("http://openai-service");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface(),
                ANTHROPIC_MESSAGES.getValue(), translated(TranslatorRef.inline(
                        new Translator(ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS, "http://translator/to-chat-completions/")))));

        // the deployment base url serves the pass-through interface, the translator serves the translated one
        assertEquals("http://openai-service", resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
        assertEquals("http://translator/to-chat-completions", resolveServingEndpoint(model, ANTHROPIC_MESSAGES, NO_TRANSLATORS));
        assertTrue(isInterfaceDeclared(model, ANTHROPIC_MESSAGES));
        assertEquals("http://translator/to-chat-completions/anthropic/v1/messages",
                resolveRequestUri(model, ANTHROPIC_MESSAGES, NO_TRANSLATORS, "/anthropic/v1/messages", null));
    }

    @Test
    void translatedInterfaceIsServedByNoBaseUrl() {
        // mode alone decides, so what a request is routed to and what it is charged for cannot disagree
        Model model = new Model();
        model.setBaseUrl("http://openai-service");
        DeploymentInterface anthropic = translated(TranslatorRef.inline(
                new Translator(null, OPENAI_CHAT_COMPLETIONS, "http://translator")));
        anthropic.setBaseUrl("http://ignored");
        model.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), anthropic));

        assertEquals("http://translator", resolveServingEndpoint(model, ANTHROPIC_MESSAGES, NO_TRANSLATORS));
    }

    @Test
    void namedTranslatorServesTheInterfaceOnceRegistered() {
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(),
                translated(TranslatorRef.named("anthropicMessagesToOpenaiChatCompletions"))));

        // the reference alone declares the interface; whether the name resolves is a serving-time
        // question, so a name with no registry entry is advertised and serves nothing — callers answer 503
        assertTrue(isInterfaceDeclared(model, ANTHROPIC_MESSAGES));
        assertNull(resolveServingEndpoint(model, ANTHROPIC_MESSAGES, NO_TRANSLATORS));

        // registering the entry serves the interface, with nothing relinked on the model
        Map<String, Translator> translators = Map.of("anthropicMessagesToOpenaiChatCompletions",
                new Translator(ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS, "http://translator"));
        assertEquals("http://translator", resolveServingEndpoint(model, ANTHROPIC_MESSAGES, translators));

        // and an edit to the entry is what the next request reads, never a copy frozen into the model
        Map<String, Translator> edited = Map.of("anthropicMessagesToOpenaiChatCompletions",
                new Translator(ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS, "http://translator-v2"));
        assertEquals("http://translator-v2", resolveServingEndpoint(model, ANTHROPIC_MESSAGES, edited));
    }

    @Test
    void translatedInterfaceDoesNotFallBackToTheLegacyEndpoint() {
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), translated(TranslatorRef.named("missing"))));

        assertNull(resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
    }

    @Test
    void modeDefaultsToPassthrough() {
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        DeploymentInterface translated = new DeploymentInterface("http://translator");
        translated.setMode(InterfaceMode.TRANSLATOR);
        model.setInterfaces(Map.of(
                OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter"),
                ANTHROPIC_MESSAGES.getValue(), translated));

        assertEquals(InterfaceMode.TRANSLATOR, resolveMode(model, ANTHROPIC_MESSAGES));
        // declared without a mode, and served by the legacy endpoint, are both pass-through
        assertEquals(InterfaceMode.PASSTHROUGH, resolveMode(model, OPENAI_RESPONSES));
        assertEquals(InterfaceMode.PASSTHROUGH, resolveMode(model, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void interfacesWinWhenBothDeclared() {
        Model model = new Model();
        model.setResponsesEndpoint("http://legacy/openai/v1/responses");
        model.setInterfaces(Map.of(
                OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));

        assertEquals("http://adapter", resolveServingEndpoint(model, OPENAI_RESPONSES, NO_TRANSLATORS));
    }

    @Test
    void neitherDeclared() {
        Model model = new Model();

        assertNull(resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
        assertNull(resolveServingEndpoint(model, OPENAI_RESPONSES, NO_TRANSLATORS));
        assertNull(resolveServingEndpoint(model, OPENAI_EMBEDDINGS, NO_TRANSLATORS));
        assertNull(resolveServingEndpoint(model, ANTHROPIC_MESSAGES, NO_TRANSLATORS));
        assertFalse(isInterfaceDeclared(model, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void embeddingsDeclaredExplicitly() {
        Model model = new Model();
        model.setType(ModelType.EMBEDDING);
        model.setInterfaces(Map.of(
                OPENAI_EMBEDDINGS.getValue(), new DeploymentInterface("http://adapter:5000/")));

        assertEquals("http://adapter:5000", resolveServingEndpoint(model, OPENAI_EMBEDDINGS, NO_TRANSLATORS));
        assertTrue(isInterfaceDeclared(model, OPENAI_EMBEDDINGS));

        // declaring embeddings alone does not make it a chat-completions deployment
        assertNull(resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
    }

    @Test
    void embeddingsNotServedByChatCompletionsInterface() {
        Model model = new Model();
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter:5000")));

        // the typed map is strict: a chat-only declaration does not serve /embeddings
        assertNull(resolveServingEndpoint(model, OPENAI_EMBEDDINGS, NO_TRANSLATORS));
        assertFalse(isInterfaceDeclared(model, OPENAI_EMBEDDINGS));
    }

    @Test
    void legacyEndpointServesEmbeddingsAndDeclaresThemForAnEmbeddingModel() {
        Model model = new Model();
        model.setType(ModelType.EMBEDDING);
        model.setEndpoint("http://host/openai/deployments/ada/embeddings");

        assertEquals("http://host/openai/deployments/ada/embeddings", resolveServingEndpoint(model, OPENAI_EMBEDDINGS, NO_TRANSLATORS));
        assertTrue(isInterfaceDeclared(model, OPENAI_EMBEDDINGS));
        // an embedding model is not a chat-completions one, whatever its single endpoint also serves
        assertFalse(isInterfaceDeclared(model, OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://host/openai/deployments/ada/embeddings", resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
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
            assertTrue(isInterfaceDeclared(model, OPENAI_CHAT_COMPLETIONS));
            assertFalse(isInterfaceDeclared(model, OPENAI_EMBEDDINGS));
            // the untyped endpoint still serves the whole deployments-POST family
            assertEquals(model.getEndpoint(), resolveServingEndpoint(model, OPENAI_EMBEDDINGS, NO_TRANSLATORS));
        }
    }

    @Test
    void applicationsAndInterceptorsDeclareChatCompletionsFromTheLegacyEndpoint() {
        Application application = new Application();
        application.setEndpoint("http://host/chat/completions");
        Interceptor interceptor = new Interceptor();
        interceptor.setEndpoint("http://host/chat/completions");

        assertTrue(isInterfaceDeclared(application, OPENAI_CHAT_COMPLETIONS));
        assertFalse(isInterfaceDeclared(application, OPENAI_EMBEDDINGS));
        assertTrue(isInterfaceDeclared(interceptor, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void embeddingsPreferLegacyEndpointOverChatInterface() {
        Model model = new Model();
        model.setEndpoint("http://host/openai/deployments/ada/embeddings");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://chat-adapter")));

        // the chat-completions interface never serves embeddings; the untyped legacy endpoint does
        assertEquals("http://chat-adapter", resolveServingEndpoint(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS));
        assertEquals("http://host/openai/deployments/ada/embeddings", resolveServingEndpoint(model, OPENAI_EMBEDDINGS, NO_TRANSLATORS));
    }

    @Test
    void legacyEndpointServesEmbeddingsButNotResponses() {
        Model model = new Model();
        model.setEndpoint("http://host/chat/completions");

        assertEquals("http://host/chat/completions", resolveServingEndpoint(model, OPENAI_EMBEDDINGS, NO_TRANSLATORS));
        assertNull(resolveServingEndpoint(model, OPENAI_RESPONSES, NO_TRANSLATORS));
    }

    @Test
    void embeddingsAndChatInterfacesRouteToTheirOwnAdapters() {
        Model model = new Model();
        model.setName("embedding-ada");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://chat-adapter"),
                OPENAI_EMBEDDINGS.getValue(), new DeploymentInterface("http://embeddings-adapter")));

        assertEquals("http://embeddings-adapter/openai/deployments/embedding-ada/embeddings",
                resolveRequestUri(model, OPENAI_EMBEDDINGS, NO_TRANSLATORS, "/openai/deployments/embedding-ada/embeddings", null));
        assertEquals("http://chat-adapter/openai/deployments/embedding-ada/chat/completions",
                resolveRequestUri(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS, "/openai/deployments/embedding-ada/chat/completions", null));
    }

    @Test
    void responsesBaseUriAppendsThePathOnlyInTheInterfacesFlow() {
        Model interfaced = new Model();
        interfaced.setInterfaces(Map.of(
                OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter/")));
        assertEquals("http://adapter/openai/v1/responses", resolveResponsesBaseUri(interfaced, NO_TRANSLATORS));

        Model legacy = new Model();
        legacy.setResponsesEndpoint("http://legacy/custom/responses");
        // a pre-interfaces endpoint is a complete url, so nothing is appended to it
        assertEquals("http://legacy/custom/responses", resolveResponsesBaseUri(legacy, NO_TRANSLATORS));
    }

    @Test
    void requestUriAppendsTheIngressPathToTheBaseUrl() {
        Model model = new Model();
        model.setName("als-2");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://localhost:6001/")));

        assertEquals("http://localhost:6001/openai/deployments/als-2/chat/completions",
                resolveRequestUri(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS, "/openai/deployments/als-2/chat/completions", null));
    }

    @Test
    void requestUriRewritesInterceptorPseudoDeploymentSegment() {
        // an interceptor calls back into Core with the literal pseudo id "interceptor" in the path
        Model model = new Model();
        model.setName("essay-assistant-gpt");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://localhost:5025")));

        assertEquals("http://localhost:5025/openai/deployments/essay-assistant-gpt/chat/completions?api-version=2024-08-06",
                resolveRequestUri(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS,
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
                resolveRequestUri(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS,
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
                resolveRequestUri(application, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS, "/openai/deployments/app-tst/chat/completions", null));
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
                resolveRequestUri(overridden, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS, "/openai/deployments/openai-gpt/chat/completions", null));

        // a name is already in url form: a custom application carries its encoded resource url as its name
        Application application = new Application();
        application.setName("applications/bucket/My%20App");
        application.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));

        assertEquals("http://adapter/openai/deployments/applications/bucket/My%20App/chat/completions",
                resolveRequestUri(application, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS,
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
                resolveRequestUri(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS,
                        "/openai/deployments/openai-gpt-5.4-mini/chat/completions", "api-version=2025-01-01-preview"));
    }

    private static DeploymentInterface translated(TranslatorRef translator) {
        DeploymentInterface declared = new DeploymentInterface();
        declared.setMode(InterfaceMode.TRANSLATOR);
        declared.setTranslator(translator);
        return declared;
    }

    @Test
    void requestUriLeavesPathWithoutDeploymentSegmentUnchanged() {
        Model model = new Model();
        model.setName("my-model");
        model.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));

        assertEquals("http://adapter/some/other/path",
                resolveRequestUri(model, OPENAI_CHAT_COMPLETIONS, NO_TRANSLATORS, "/some/other/path", null));
    }
}
