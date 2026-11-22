package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.SpendingLimitMode;
import com.epam.aidial.core.config.SpendingLimits;
import com.epam.aidial.core.server.validation.ValidationModule;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpendingLimitsConfigTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new ValidationModule()).build();

    @Test
    void testDefaultsAreOffAndOrg() throws Exception {
        Config config = mapper.readValue("{}", Config.class);

        assertEquals(SpendingLimitMode.OFF, config.getSpendingLimits().getMode());
        assertEquals("ORG", config.getSpendingLimits().getDefaultCostObject());
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "shadow", "enforce", "ENFORCE"})
    void testModeIsReadCaseInsensitively(String mode) throws Exception {
        SpendingLimits limits = mapper.readValue("{\"mode\":\"" + mode + "\"}", SpendingLimits.class);

        assertEquals(SpendingLimitMode.valueOf(mode.toUpperCase()), limits.getMode());
    }

    @Test
    void testUnknownModeFails() {
        assertThrows(Exception.class, () -> mapper.readValue("{\"mode\":\"strict\"}", SpendingLimits.class));
    }

    @Test
    void testValidCostObjectIsAccepted() throws Exception {
        SpendingLimits limits = mapper.readValue("{\"defaultCostObject\":\"personal.v1\"}", SpendingLimits.class);

        assertEquals("personal.v1", limits.getDefaultCostObject());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a/b", "a%20b", "a b"})
    void testInvalidCostObjectFailsAtLoad(String name) {
        String json = "{\"spendingLimits\":{\"mode\":\"enforce\",\"defaultCostObject\":\"" + name + "\"}}";

        assertThrows(Exception.class, () -> mapper.readValue(json, Config.class));
    }
}
