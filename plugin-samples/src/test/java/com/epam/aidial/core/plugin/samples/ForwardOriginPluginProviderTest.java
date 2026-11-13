package com.epam.aidial.core.plugin.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardOriginPluginProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ForwardOriginPluginProvider provider = new ForwardOriginPluginProvider();

    @Test
    void testValidate() {
        assertTrue(provider.validate(MAPPER.createObjectNode().put("deployment", "gpt-4o")).isEmpty());
        assertFalse(provider.validate(MAPPER.createObjectNode()).isEmpty());
        assertFalse(provider.validate(MAPPER.createObjectNode().put("deployment", " ")).isEmpty());
        assertFalse(provider.validate(MAPPER.createObjectNode().put("deployment", 1)).isEmpty());
    }

    @Test
    void testDeploymentActionsKeepTheirAction() {
        ObjectNode body = MAPPER.createObjectNode();

        assertEquals("/openai/deployments/gpt-4o/chat/completions?api-version=1",
                ForwardOriginPluginProvider.target("/openai/deployments/router/chat/completions?api-version=1", "gpt-4o", body));
        assertEquals("/openai/deployments/gpt-4o/embeddings",
                ForwardOriginPluginProvider.target("/openai/deployments/router/embeddings", "gpt-4o", body));
        assertEquals("/openai/deployments/gpt-4o/completions",
                ForwardOriginPluginProvider.target("/openai/deployments/router/completions", "gpt-4o", body));
        assertFalse(body.has("model"));
    }

    @Test
    void testModelInTheBodyForTheOtherInterfaces() {
        for (String uri : new String[] {"/openai/v1/chat/completions", "/openai/v1/responses", "/anthropic/v1/messages"}) {
            ObjectNode body = MAPPER.createObjectNode().put("model", "router");

            assertEquals(uri, ForwardOriginPluginProvider.target(uri, "gpt-4o", body));
            assertEquals("gpt-4o", body.path("model").asText());
        }
    }
}
