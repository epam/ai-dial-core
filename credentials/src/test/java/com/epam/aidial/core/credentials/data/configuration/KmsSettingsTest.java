package com.epam.aidial.core.credentials.data.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KmsSettingsTest {

    @ParameterizedTest
    @CsvSource(value = {"NIL,false", "unencrypted,false", "foo,false", "aws,true", "AWS,true", "azure,true", "gcp,true"}, nullValues = "NIL")
    void testEnabledOnlyForSupportedProvider(String provider, boolean expected) {
        assertEquals(expected, KmsSettings.builder().provider(provider).build().isEnabled());
    }

    @ParameterizedTest
    @CsvSource(value = {"NIL,true", "unencrypted,true", "foo,false", "aws,false"}, nullValues = "NIL")
    void testUnencryptedOnlyWithoutProviderOrUnencrypted(String provider, boolean expected) {
        assertEquals(expected, KmsSettings.builder().provider(provider).build().isUnencrypted());
    }

    @Test
    void testJsonRoundTripIgnoresDerivedProperties() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        KmsSettings settings = mapper.readValue("{\"provider\":\"aws\",\"keyId\":\"k\"}", KmsSettings.class);

        assertTrue(settings.isEnabled());
        assertEquals("k", settings.getKeyId());
        JsonNode written = mapper.valueToTree(settings);
        assertFalse(written.has("enabled") || written.has("unencrypted"), written.toString());
    }
}
