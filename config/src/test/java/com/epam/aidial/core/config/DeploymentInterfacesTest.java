package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentInterfacesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testInterfaceTypeFromValue() {
        assertEquals(InterfaceType.OPENAI_CHAT_COMPLETIONS, InterfaceType.fromValue("openaiChatCompletions"));
        assertEquals(InterfaceType.OPENAI_RESPONSES, InterfaceType.fromValue("openaiResponses"));
        // anthropicMessages / geminiGenerateContent are reserved for a future task and not defined yet
        assertThrows(IllegalArgumentException.class, () -> InterfaceType.fromValue("anthropicMessages"));
        assertThrows(IllegalArgumentException.class, () -> InterfaceType.fromValue("geminiGenerateContent"));
        assertThrows(IllegalArgumentException.class, () -> InterfaceType.fromValue("unknown"));
        assertThrows(IllegalArgumentException.class, () -> InterfaceType.fromValue(null));
    }

    @Test
    public void testHelpers() {
        Model model = new Model();
        model.setInterfaces(
                Map.of(
                        InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter:5000")
                )
        );

        assertTrue(model.supportsInterface(InterfaceType.OPENAI_CHAT_COMPLETIONS));
        assertEquals("http://adapter:5000", model.getInterfaceBaseUrl(InterfaceType.OPENAI_CHAT_COMPLETIONS));
        assertFalse(model.supportsInterface(InterfaceType.OPENAI_RESPONSES));
        assertNull(model.getInterfaceBaseUrl(InterfaceType.OPENAI_RESPONSES));
    }

    @Test
    public void testEmptyInterfaces() {
        Model model = new Model();
        assertFalse(model.supportsInterface(InterfaceType.OPENAI_CHAT_COMPLETIONS));
        assertNull(model.getInterfaceBaseUrl(InterfaceType.OPENAI_CHAT_COMPLETIONS));
        assertNull(model.getInterfaceBaseUrl(null));
    }

    @Test
    public void testDeserializeBaseUrlAndAlias() throws Exception {
        // getInterfaceBaseUrl strips the trailing slash so it can be concatenated with a request path
        String snakeCase = "{\"interfaces\":{\"openaiChatCompletions\":{\"base_url\":\"http://a:5000/\"}}}";
        Model model = MAPPER.readValue(snakeCase, Model.class);
        assertEquals("http://a:5000", model.getInterfaceBaseUrl(InterfaceType.OPENAI_CHAT_COMPLETIONS));

        String camelCase = "{\"interfaces\":{\"openaiResponses\":{\"baseUrl\":\"http://b:6000\"}}}";
        Model aliased = MAPPER.readValue(camelCase, Model.class);
        assertEquals("http://b:6000", aliased.getInterfaceBaseUrl(InterfaceType.OPENAI_RESPONSES));
    }

    @Test
    public void testSerializeEmitsBaseUrl() throws Exception {
        Application application = new Application();
        application.setInterfaces(
                Map.of(
                        InterfaceType.OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter:5000")
                )
        );

        String json = MAPPER.writeValueAsString(application);
        assertTrue(json.contains("\"base_url\":\"http://adapter:5000\""), json);

        Application back = MAPPER.readValue(json, Application.class);
        assertEquals("http://adapter:5000", back.getInterfaceBaseUrl(InterfaceType.OPENAI_RESPONSES));
    }
}
