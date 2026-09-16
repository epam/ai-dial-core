package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.UpstreamInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.epam.aidial.core.config.InterfaceType.ANTHROPIC_MESSAGES;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_EMBEDDINGS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_RESPONSES;
import static com.epam.aidial.core.server.util.UpstreamInterfaceUtil.resolveEndpoint;
import static com.epam.aidial.core.server.util.UpstreamInterfaceUtil.resolveExtraData;
import static com.epam.aidial.core.server.util.UpstreamInterfaceUtil.resolveKey;
import static com.epam.aidial.core.server.util.UpstreamInterfaceUtil.resolveSecretExtraData;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class UpstreamInterfaceUtilTest {

    @Test
    void legacyEndpointServesEveryInterfaceButResponses() {
        Upstream upstream = new Upstream();
        upstream.setEndpoint("http://host/openai/deployments/gpt/chat/completions");

        assertEquals(upstream.getEndpoint(), resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
        assertEquals(upstream.getEndpoint(), resolveEndpoint(upstream, OPENAI_EMBEDDINGS));
        // the confusion interfaces exists to remove: the untyped endpoint has been serving Anthropic too
        assertEquals(upstream.getEndpoint(), resolveEndpoint(upstream, ANTHROPIC_MESSAGES));
        assertNull(resolveEndpoint(upstream, OPENAI_RESPONSES));
    }

    @Test
    void legacyResponsesEndpoint() {
        Upstream upstream = new Upstream();
        upstream.setResponsesEndpoint("http://host/openai/v1/responses");

        assertEquals("http://host/openai/v1/responses", resolveEndpoint(upstream, OPENAI_RESPONSES));
        assertNull(resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void neitherDeclared() {
        Upstream upstream = new Upstream();

        assertNull(resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
        assertNull(resolveEndpoint(upstream, OPENAI_EMBEDDINGS));
        assertNull(resolveEndpoint(upstream, OPENAI_RESPONSES));
        assertNull(resolveEndpoint(upstream, ANTHROPIC_MESSAGES));
    }

    @Test
    void explicitInterfaceEndpointsRouteEachApiToItsOwnUrl() {
        Upstream upstream = new Upstream();
        upstream.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new UpstreamInterface("https://api.fireworks.ai/inference/v1/chat/completions"),
                OPENAI_RESPONSES.getValue(), new UpstreamInterface("https://api.fireworks.ai/inference/v1/responses"),
                ANTHROPIC_MESSAGES.getValue(), new UpstreamInterface("https://api.fireworks.ai/inference/v1/messages")));

        assertEquals("https://api.fireworks.ai/inference/v1/chat/completions", resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
        assertEquals("https://api.fireworks.ai/inference/v1/responses", resolveEndpoint(upstream, OPENAI_RESPONSES));
        assertEquals("https://api.fireworks.ai/inference/v1/messages", resolveEndpoint(upstream, ANTHROPIC_MESSAGES));
        // the map is a whitelist: an undeclared interface falls through to the legacy fields
        assertNull(resolveEndpoint(upstream, OPENAI_EMBEDDINGS));
    }

    @Test
    void baseUrlCompletesTheInterfacesThatDeclareNoEndpoint() {
        Upstream upstream = new Upstream();
        upstream.setBaseUrl("https://api.fireworks.ai/inference");
        upstream.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new UpstreamInterface(),
                OPENAI_RESPONSES.getValue(), new UpstreamInterface(),
                OPENAI_EMBEDDINGS.getValue(), new UpstreamInterface(),
                ANTHROPIC_MESSAGES.getValue(), new UpstreamInterface()));

        assertEquals("https://api.fireworks.ai/inference/v1/chat/completions", resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
        assertEquals("https://api.fireworks.ai/inference/v1/responses", resolveEndpoint(upstream, OPENAI_RESPONSES));
        assertEquals("https://api.fireworks.ai/inference/v1/embeddings", resolveEndpoint(upstream, OPENAI_EMBEDDINGS));
        assertEquals("https://api.fireworks.ai/inference/v1/messages", resolveEndpoint(upstream, ANTHROPIC_MESSAGES));
    }

    @Test
    void interfaceEndpointWinsOverBaseUrl() {
        Upstream upstream = new Upstream();
        upstream.setBaseUrl("https://api.fireworks.ai/inference");
        upstream.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new UpstreamInterface(),
                ANTHROPIC_MESSAGES.getValue(), new UpstreamInterface("https://api.fireworks.ai/inference/something-else/v1/messages")));

        assertEquals("https://api.fireworks.ai/inference/v1/chat/completions", resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
        assertEquals("https://api.fireworks.ai/inference/something-else/v1/messages", resolveEndpoint(upstream, ANTHROPIC_MESSAGES));
    }

    @Test
    void baseUrlStripsTrailingSlash() {
        Upstream upstream = new Upstream();
        upstream.setBaseUrl("https://api.fireworks.ai/inference/");
        upstream.setInterfaces(Map.of(OPENAI_CHAT_COMPLETIONS.getValue(), new UpstreamInterface()));

        assertEquals("https://api.fireworks.ai/inference/v1/chat/completions", resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void baseUrlAppliesOnlyToDeclaredInterfaces() {
        Upstream upstream = new Upstream();
        upstream.setBaseUrl("https://api.fireworks.ai/inference");
        upstream.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), new UpstreamInterface()));

        assertEquals("https://api.fireworks.ai/inference/v1/messages", resolveEndpoint(upstream, ANTHROPIC_MESSAGES));
        assertNull(resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void interfacesWinOverLegacyFieldsPerType() {
        Upstream upstream = new Upstream();
        upstream.setEndpoint("http://legacy/v1/chat/completions");
        upstream.setResponsesEndpoint("http://legacy/v1/responses");
        upstream.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), new UpstreamInterface("http://anthropic/v1/messages")));

        assertEquals("http://anthropic/v1/messages", resolveEndpoint(upstream, ANTHROPIC_MESSAGES));
        // the legacy fields keep serving the types the map says nothing about
        assertEquals("http://legacy/v1/chat/completions", resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://legacy/v1/responses", resolveEndpoint(upstream, OPENAI_RESPONSES));
    }

    @Test
    void declaredInterfaceWithoutEndpointOrBaseUrlResolvesToNothing() {
        Upstream upstream = new Upstream();
        upstream.setEndpoint("http://legacy/v1/chat/completions");
        upstream.setInterfaces(Map.of(OPENAI_CHAT_COMPLETIONS.getValue(), new UpstreamInterface()));

        // declaring the interface opts out of the legacy field, so nothing is left to complete it
        assertNull(resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void keyAndExtraDataFallBackToTheUpstreamLevel() {
        Upstream upstream = new Upstream();
        upstream.setBaseUrl("https://api.fireworks.ai/inference");
        upstream.setKey("sk-proj-shared");
        upstream.setExtraData("{\"region\":\"us-east-1\"}");
        upstream.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new UpstreamInterface(),
                OPENAI_RESPONSES.getValue(), new UpstreamInterface()));

        for (InterfaceType type : new InterfaceType[] {OPENAI_CHAT_COMPLETIONS, OPENAI_RESPONSES}) {
            assertEquals("sk-proj-shared", resolveKey(upstream, type));
            assertEquals("{\"region\":\"us-east-1\"}", resolveExtraData(upstream, type));
            assertNull(resolveSecretExtraData(upstream, type));
        }
    }

    @Test
    void eachOverridableFieldFallsBackIndependently() {
        // the spec's case: anthropicMessages overrides key and adds secretExtraData, and still
        // inherits the upstream's extraData
        Upstream upstream = new Upstream();
        upstream.setBaseUrl("https://api.fireworks.ai/inference");
        upstream.setKey("sk-proj-shared");
        upstream.setExtraData("{\"region\":\"us-east-1\"}");
        UpstreamInterface anthropic = new UpstreamInterface();
        anthropic.setKey("foo-bar-another");
        anthropic.setSecretExtraData("{\"region\":\"us-east-3\"}");
        upstream.setInterfaces(Map.of(
                OPENAI_CHAT_COMPLETIONS.getValue(), new UpstreamInterface(),
                ANTHROPIC_MESSAGES.getValue(), anthropic));

        assertEquals("foo-bar-another", resolveKey(upstream, ANTHROPIC_MESSAGES));
        assertEquals("{\"region\":\"us-east-1\"}", resolveExtraData(upstream, ANTHROPIC_MESSAGES));
        assertEquals("{\"region\":\"us-east-3\"}", resolveSecretExtraData(upstream, ANTHROPIC_MESSAGES));

        // the sibling interface is untouched by the override
        assertEquals("sk-proj-shared", resolveKey(upstream, OPENAI_CHAT_COMPLETIONS));
        assertNull(resolveSecretExtraData(upstream, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void legacyFieldsYieldPerTypeToTheInterfacesThatSupersedeThem() throws Exception {
        // the spec's backward-compatibility case, verbatim: endpoint is superseded by the declared
        // openaiChatCompletions, while responsesEndpoint still serves the undeclared openaiResponses
        String json = """
                {
                    "id": "1",
                    "weight": 1,
                    "tier": 1,
                    "endpoint": "https://api.fireworks.ai/inference/v1/chat/completions",
                    "responsesEndpoint": "https://api.fireworks.ai/inference/v1/responses",
                    "key": "sk-proj-9UThW",
                    "extraData": {"region": "us-east-1"},
                    "baseUrl": "https://api.fireworks.ai/inference",
                    "interfaces": {
                        "openaiChatCompletions": {},
                        "anthropicMessages": {}
                    }
                }
                """;
        Upstream upstream = new ObjectMapper().readValue(json, Upstream.class);

        assertEquals("https://api.fireworks.ai/inference/v1/chat/completions",
                resolveEndpoint(upstream, OPENAI_CHAT_COMPLETIONS));
        assertEquals("https://api.fireworks.ai/inference/v1/messages",
                resolveEndpoint(upstream, ANTHROPIC_MESSAGES));
        assertEquals("https://api.fireworks.ai/inference/v1/responses",
                resolveEndpoint(upstream, OPENAI_RESPONSES));
        // key and extraData are shared by every interface, declared or not
        for (InterfaceType type : InterfaceType.values()) {
            assertEquals("sk-proj-9UThW", resolveKey(upstream, type));
            assertEquals("{\"region\":\"us-east-1\"}", resolveExtraData(upstream, type));
        }
    }

    @Test
    void overridesApplyOnlyToDeclaredInterfaces() {
        Upstream upstream = new Upstream();
        upstream.setEndpoint("http://legacy/v1/chat/completions");
        upstream.setKey("upstream-key");
        UpstreamInterface anthropic = new UpstreamInterface("http://anthropic/v1/messages");
        anthropic.setKey("anthropic-key");
        upstream.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), anthropic));

        assertEquals("anthropic-key", resolveKey(upstream, ANTHROPIC_MESSAGES));
        // an undeclared interface sees the upstream's own credential, untouched
        assertEquals("upstream-key", resolveKey(upstream, OPENAI_CHAT_COMPLETIONS));
    }
}
