package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PluginTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pluginCannotBeBuiltWithoutType() {
        assertThrows(IllegalArgumentException.class, () -> new Plugin(null, null));
        assertThrows(IllegalArgumentException.class, () -> new Plugin(" ", null));
    }

    @Test
    void missingTypeIsRejectedOnRead() {
        assertThrows(MismatchedInputException.class, () -> MAPPER.readValue("{\"settings\": {}}", Plugin.class));
    }

    @Test
    void absentSettingsReadAsEmptyObject() throws Exception {
        Plugin plugin = MAPPER.readValue("{\"type\": \"test-echo\"}", Plugin.class);

        assertEquals("test-echo", plugin.getType());
        assertEquals(MAPPER.createObjectNode(), plugin.getSettings());
    }

    @Test
    void settingsAreKeptAsWritten() throws Exception {
        Plugin plugin = MAPPER.readValue("{\"type\": \"t\", \"settings\": {\"limits\": {\"rpm\": 10}}}", Plugin.class);

        assertEquals(10, plugin.getSettings().at("/limits/rpm").asInt());
    }

    @Test
    void settingsMustBeAnObject() {
        assertThrows(MismatchedInputException.class, () -> MAPPER.readValue("{\"type\": \"t\", \"settings\": \"x\"}", Plugin.class));
    }

    @Test
    void unknownFieldIsRejectedOnRead() {
        assertThrows(UnrecognizedPropertyException.class,
                () -> MAPPER.readValue("{\"type\": \"t\", \"kind\": \"origin\"}", Plugin.class));
    }

    @Test
    void configReadsThePluginsByName() throws Exception {
        Config config = MAPPER.readValue("{\"plugins\": {\"p1\": {\"type\": \"t\"}}}", Config.class);

        assertEquals("t", config.getPlugins().get("p1").getType());
    }
}
