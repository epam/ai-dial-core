package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class UpstreamAuthConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper CASE_INSENSITIVE_MAPPER = new ObjectMapper().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);

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

    @ParameterizedTest
    @CsvSource({"bearer, BEARER", "Bearer, BEARER", "api_key, API_KEY", "' bearer ', BEARER"})
    void stringFormIsCaseInsensitive(String value, UpstreamAuthType expected) throws Exception {
        String json = MAPPER.writeValueAsString(value);

        for (ObjectMapper mapper : new ObjectMapper[] {MAPPER, CASE_INSENSITIVE_MAPPER}) {
            UpstreamAuthConfig config = mapper.readValue(json, UpstreamAuthConfig.class);

            assertEquals(expected, config.type());
            assertNull(config.headerName());
        }
    }

    @Test
    void stringFormRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> UpstreamAuthConfig.fromString(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEARER", "bearer", "Bearer", "api_key", "Api_Key", "custom_header", " bearer", "bearer\n",
            "", " ", "basic", "custom-header", "CUSTOM HEADER", "ı", "BEARERİ"})
    void stringFormIsConsistentWithObjectForm(String value) throws Exception {
        String stringForm = MAPPER.writeValueAsString(value);
        String objectForm = "{\"type\": " + stringForm + "}";

        // the object form is parsed by the application mapper (case-insensitive enums), the string form must agree with it;
        // null means rejected, which includes CUSTOM_HEADER in both forms as it requires headerName
        UpstreamAuthType expected = parseType(CASE_INSENSITIVE_MAPPER, objectForm);

        assertEquals(expected, parseType(CASE_INSENSITIVE_MAPPER, stringForm));
        assertEquals(expected, parseType(MAPPER, stringForm));
    }

    private static UpstreamAuthType parseType(ObjectMapper mapper, String json) {
        try {
            UpstreamAuthConfig config = mapper.readValue(json, UpstreamAuthConfig.class);
            return config == null ? null : config.type();
        } catch (Exception e) {
            return null;
        }
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
