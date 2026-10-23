package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class UpstreamAuthConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void authTypeDefaultsToApiKey() throws Exception {
        assertEquals(UpstreamAuthConfig.API_KEY, new Upstream().getAuthType());
        assertEquals(UpstreamAuthConfig.API_KEY, MAPPER.readValue("{}", Upstream.class).getAuthType());
    }

    @Test
    void stringFormParsesToTypeWithoutHeaderName() throws Exception {
        Upstream upstream = MAPPER.readValue("{\"authType\": \"BEARER\"}", Upstream.class);

        assertEquals(UpstreamAuthType.BEARER, upstream.getAuthType().type());
        assertNull(upstream.getAuthType().headerName());
    }

    @Test
    void objectFormParsesCustomHeader() throws Exception {
        String json = "{\"auth_type\": {\"type\": \"CUSTOM_HEADER\", \"headerName\": \"X-Api-Token\"}}";

        UpstreamAuthConfig config = MAPPER.readValue(json, Upstream.class).getAuthType();

        assertEquals(UpstreamAuthType.CUSTOM_HEADER, config.type());
        assertEquals("X-Api-Token", config.headerName());
    }

    @Test
    void objectFormParsesPlainTypes() throws Exception {
        UpstreamAuthConfig config = MAPPER.readValue("{\"type\": \"API_KEY\"}", UpstreamAuthConfig.class);

        assertEquals(UpstreamAuthConfig.API_KEY, config);
    }

    @Test
    void customHeaderRequiresHeaderName() {
        assertThrows(JsonMappingException.class,
                () -> MAPPER.readValue("{\"type\": \"CUSTOM_HEADER\"}", UpstreamAuthConfig.class));
        assertThrows(JsonMappingException.class,
                () -> MAPPER.readValue("\"CUSTOM_HEADER\"", UpstreamAuthConfig.class));
        assertThrows(IllegalArgumentException.class, () -> new UpstreamAuthConfig(UpstreamAuthType.CUSTOM_HEADER, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "X Api", "X-Api:", "X-Api\n", "Ключ"})
    void customHeaderRejectsInvalidHeaderName(String headerName) {
        assertThrows(IllegalArgumentException.class,
                () -> new UpstreamAuthConfig(UpstreamAuthType.CUSTOM_HEADER, headerName));
    }

    @Test
    void headerNameIsOnlyAllowedForCustomHeader() {
        assertThrows(IllegalArgumentException.class, () -> new UpstreamAuthConfig(UpstreamAuthType.BEARER, "X-Api-Token"));
        assertThrows(JsonMappingException.class,
                () -> MAPPER.readValue("{\"type\": \"API_KEY\", \"headerName\": \"X-Api-Token\"}", UpstreamAuthConfig.class));
    }

    @Test
    void typeIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new UpstreamAuthConfig(null, null));
        assertThrows(JsonMappingException.class, () -> MAPPER.readValue("{}", UpstreamAuthConfig.class));
    }

    @Test
    void unknownTypeIsRejected() {
        assertThrows(JsonMappingException.class, () -> MAPPER.readValue("\"BASIC\"", UpstreamAuthConfig.class));
    }

    @Test
    void roundTrip() throws Exception {
        UpstreamAuthConfig config = new UpstreamAuthConfig(UpstreamAuthType.CUSTOM_HEADER, "X-Api-Token");

        assertEquals(config, MAPPER.readValue(MAPPER.writeValueAsString(config), UpstreamAuthConfig.class));
    }
}
