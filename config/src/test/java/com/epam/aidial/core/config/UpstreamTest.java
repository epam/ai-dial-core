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
 * State and serialization of an upstream. Which url, key and extra data serve an interface type
 * belongs to {@code UpstreamInterfaceUtil} and is covered by its own test.
 */
public class UpstreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void interfacesParseWithAndWithoutTheirOwnEndpoint() throws Exception {
        String json = """
                {
                    "baseUrl": "https://api.fireworks.ai/inference",
                    "key": "modelKey",
                    "interfaces": {
                        "openaiChatCompletions": {},
                        "openaiResponses": {},
                        "anthropicMessages": {"endpoint": "https://api.fireworks.ai/inference/something-else/v1/messages"}
                    }
                }
                """;
        Upstream upstream = MAPPER.readValue(json, Upstream.class);

        assertEquals("https://api.fireworks.ai/inference", upstream.getBaseUrl());
        assertEquals(3, upstream.getInterfaces().size());
        assertNull(upstream.getInterfaces().get(OPENAI_CHAT_COMPLETIONS.getValue()).getEndpoint());
        assertNull(upstream.getInterfaces().get(OPENAI_RESPONSES.getValue()).getEndpoint());
        assertEquals("https://api.fireworks.ai/inference/something-else/v1/messages",
                upstream.getInterfaces().get(ANTHROPIC_MESSAGES.getValue()).getEndpoint());
    }

    @Test
    void baseUrlAcceptsTheSnakeCaseAlias() throws Exception {
        Upstream upstream = MAPPER.readValue("{\"base_url\": \"https://provider\"}", Upstream.class);

        assertEquals("https://provider", upstream.getBaseUrl());
    }

    @Test
    void unknownInterfaceKeysAreTolerated() throws Exception {
        String json = """
                {
                    "endpoint": "http://host/v1/chat/completions",
                    "interfaces": {"geminiGenerateContent": {"endpoint": "http://gemini"}}
                }
                """;
        Upstream upstream = MAPPER.readValue(json, Upstream.class);

        // unknown keys parse and are kept, they simply never match a known interface type
        assertEquals("http://gemini", upstream.getInterfaces().get("geminiGenerateContent").getEndpoint());
        assertEquals("http://host/v1/chat/completions", upstream.getEndpoint());
    }

    @Test
    void interfaceOverridesParse() throws Exception {
        String json = """
                {
                    "key": "sk-proj-shared",
                    "extraData": {"region": "us-east-1"},
                    "baseUrl": "https://api.fireworks.ai/inference",
                    "interfaces": {
                        "openaiChatCompletions": {},
                        "anthropicMessages": {
                            "key": "foo-bar-another",
                            "secretExtraData": {"region": "us-east-3"}
                        }
                    }
                }
                """;
        Upstream upstream = MAPPER.readValue(json, Upstream.class);

        UpstreamInterface anthropic = upstream.getInterfaces().get(ANTHROPIC_MESSAGES.getValue());
        assertEquals("foo-bar-another", anthropic.getKey());
        // objects are captured verbatim as strings, matching the upstream-level fields
        assertEquals("{\"region\":\"us-east-3\"}", anthropic.getSecretExtraData());
        assertNull(anthropic.getExtraData());
        assertNull(upstream.getInterfaces().get(OPENAI_CHAT_COMPLETIONS.getValue()).getKey());
    }

    @Test
    void interfaceSecretsAreWriteOnly() throws Exception {
        UpstreamInterface anthropic = new UpstreamInterface("https://provider/v1/messages");
        anthropic.setKey("super-secret");
        anthropic.setSecretExtraData("{\"token\":\"x\"}");
        anthropic.setExtraData("{\"region\":\"us\"}");
        Upstream upstream = new Upstream();
        upstream.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), anthropic));

        String serialized = MAPPER.writeValueAsString(upstream);

        // the same guarantee the upstream-level key/secretExtraData carry: never serialized back out
        assertFalse(serialized.contains("super-secret"), serialized);
        assertFalse(serialized.contains("token"), serialized);
        assertTrue(serialized.contains("us"), serialized);
    }

    @Test
    void roundTripLegacy() throws Exception {
        Upstream upstream = new Upstream();
        upstream.setEndpoint("http://host/v1/chat/completions");
        upstream.setResponsesEndpoint("http://host/v1/responses");

        Upstream restored = MAPPER.readValue(MAPPER.writeValueAsString(upstream), Upstream.class);

        assertEquals("http://host/v1/chat/completions", restored.getEndpoint());
        assertEquals("http://host/v1/responses", restored.getResponsesEndpoint());
        assertNull(restored.getBaseUrl());
        assertTrue(restored.getInterfaces().isEmpty());
    }

    @Test
    void roundTripInterfaces() throws Exception {
        Upstream upstream = new Upstream();
        upstream.setEndpoint("http://host/v1/chat/completions");
        upstream.setBaseUrl("https://provider");
        upstream.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), new UpstreamInterface()));

        Upstream restored = MAPPER.readValue(MAPPER.writeValueAsString(upstream), Upstream.class);

        // legacy field is not stripped, interfaces survive
        assertEquals("http://host/v1/chat/completions", restored.getEndpoint());
        assertEquals("https://provider", restored.getBaseUrl());
        assertTrue(restored.getInterfaces().containsKey(ANTHROPIC_MESSAGES.getValue()));
    }
}
