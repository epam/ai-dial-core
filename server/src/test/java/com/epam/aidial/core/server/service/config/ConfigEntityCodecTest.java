package com.epam.aidial.core.server.service.config;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Translator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigEntityCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validEntityRoundTripsNormally() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"in": "anthropicMessages", "out": "openaiResponses", "baseUrl": "http://translator"}
                """);

        Translator translator = ConfigEntityCodec.treeToEntity(node, Translator.class);

        assertEquals(InterfaceType.ANTHROPIC_MESSAGES, translator.getIn());
        assertEquals(InterfaceType.OPENAI_RESPONSES, translator.getOut());
    }

    @Test
    void badEnumValueNamesTheField() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"in": "bogus", "out": "openaiResponses", "baseUrl": "http://translator"}
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConfigEntityCodec.treeToEntity(node, Translator.class));

        assertEquals(
                "Failed to parse Translator at \"in\": Cannot deserialize value of type "
                        + "`com.epam.aidial.core.config.InterfaceType` from String \"bogus\": not one of the values "
                        + "accepted for Enum class: [openaiResponses, openaiChatCompletions, openaiEmbeddings, anthropicMessages]",
                e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void explicitNullOnRequiredFieldNamesTheField() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"in": "anthropicMessages", "out": null, "baseUrl": "http://translator"}
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConfigEntityCodec.treeToEntity(node, Translator.class));

        assertEquals(
                "Failed to parse Translator: Cannot construct instance of "
                        + "`com.epam.aidial.core.config.Translator`, problem: Translator out cannot be null",
                e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void missingRequiredFieldNamesTheField() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"in": "anthropicMessages", "out": "openaiResponses"}
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConfigEntityCodec.treeToEntity(node, Translator.class));

        assertEquals(
                "Failed to parse Translator at \"baseUrl\": Missing required creator property 'baseUrl' (index 2)",
                e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void unrecognizedFieldNamesTheFieldWithoutJacksonBoilerplate() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"endpoint": "http://x", "totallyBogusField": 123}
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConfigEntityCodec.treeToEntity(node, Model.class));

        assertEquals("Failed to parse Model at \"totallyBogusField\": unrecognized field", e.getMessage());
        assertNotNull(e.getCause());
    }
}
