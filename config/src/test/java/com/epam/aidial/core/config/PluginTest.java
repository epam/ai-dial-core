package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PluginTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pluginCannotBeBuiltWithoutPluginId() {
        assertThrows(IllegalArgumentException.class, () -> new Plugin(null, null));
        assertThrows(IllegalArgumentException.class, () -> new Plugin(" ", null));
    }

    @Test
    void pluginIdBinds() throws Exception {
        Plugin plugin = MAPPER.readValue("{\"pluginId\": \"test-echo\"}", Plugin.class);

        assertEquals("test-echo", plugin.getPluginId());
    }

    @Test
    void typeBindsAsPluginId() throws Exception {
        Plugin plugin = MAPPER.readValue("{\"type\": \"test-echo\", \"settings\": {\"greeting\": \"hi\"}}", Plugin.class);

        assertEquals("test-echo", plugin.getPluginId());
        assertEquals("hi", plugin.getSettings().path("greeting").asText());
    }

    @Test
    void bothNamesAreRejectedOnRead() {
        JsonMappingException error = assertThrows(JsonMappingException.class,
                () -> MAPPER.readValue("{\"pluginId\": \"test-echo\", \"type\": \"test-echo\"}", Plugin.class));

        assertTrue(error.getMessage().contains("type"), error.getMessage());
    }

    @Test
    void missingPluginIdIsRejectedOnRead() {
        assertThrows(JsonMappingException.class, () -> MAPPER.readValue("{\"settings\": {}}", Plugin.class));
    }

    @Test
    void blankPluginIdIsRejectedOnRead() {
        assertThrows(JsonMappingException.class, () -> MAPPER.readValue("{\"pluginId\": \" \"}", Plugin.class));
        assertThrows(JsonMappingException.class, () -> MAPPER.readValue("{\"type\": \"\"}", Plugin.class));
    }

    @Test
    void serializedPluginHasPluginIdAndNoType() throws Exception {
        Plugin plugin = MAPPER.readValue("{\"type\": \"test-echo\"}", Plugin.class);

        JsonNode written = MAPPER.valueToTree(plugin);

        assertEquals("test-echo", written.path("pluginId").asText());
        assertFalse(written.has("type"), written.toString());
    }

    @Test
    void absentSettingsReadAsEmptyObject() throws Exception {
        Plugin plugin = MAPPER.readValue("{\"pluginId\": \"test-echo\"}", Plugin.class);

        assertEquals("test-echo", plugin.getPluginId());
        assertEquals(MAPPER.createObjectNode(), plugin.getSettings());
    }

    @Test
    void settingsAreKeptAsWritten() throws Exception {
        Plugin plugin = MAPPER.readValue("{\"pluginId\": \"t\", \"settings\": {\"limits\": {\"rpm\": 10}}}", Plugin.class);

        assertEquals(10, plugin.getSettings().at("/limits/rpm").asInt());
    }

    @Test
    void settingsMustBeAnObject() {
        assertThrows(MismatchedInputException.class, () -> MAPPER.readValue("{\"pluginId\": \"t\", \"settings\": \"x\"}", Plugin.class));
    }

    @Test
    void unknownFieldIsRejectedOnRead() {
        assertThrows(UnrecognizedPropertyException.class,
                () -> MAPPER.readValue("{\"pluginId\": \"t\", \"kind\": \"origin\"}", Plugin.class));
    }

    @Test
    void configReadsThePluginsByName() throws Exception {
        Config config = MAPPER.readValue("{\"plugins\": {\"p1\": {\"pluginId\": \"t\"}, \"p2\": {\"type\": \"u\"}}}", Config.class);

        assertEquals("t", config.getPlugins().get("p1").getPluginId());
        assertEquals("u", config.getPlugins().get("p2").getPluginId());
    }
}
