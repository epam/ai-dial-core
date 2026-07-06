package com.epam.aidial.core.credentials.databind;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleElementArrayOrStringDeserializerTest {

    @Test
    void parsesScalarStringResource() {
        String json = """
                {"resource": "https://gitlab.com", "authorization_servers": ["https://gitlab.com"]}""";

        AuthorizationServerProtectedResourceMetadata metadata =
                JsonMapperUtil.convertToObject(json, AuthorizationServerProtectedResourceMetadata.class);

        assertEquals("https://gitlab.com", metadata.getResource());
    }

    @Test
    void unwrapsSingleElementArrayResource() {
        String json = """
                {"resource": ["https://gitlab.com"], "authorization_servers": ["https://gitlab.com"]}""";

        AuthorizationServerProtectedResourceMetadata metadata =
                JsonMapperUtil.convertToObject(json, AuthorizationServerProtectedResourceMetadata.class);

        assertEquals("https://gitlab.com", metadata.getResource());
    }

    @Test
    void rejectsMultiElementArrayResource() {
        String json = """
                {"resource": ["https://gitlab.com", "https://example.com"]}""";

        assertThrows(IllegalArgumentException.class,
                () -> JsonMapperUtil.convertToObject(json, AuthorizationServerProtectedResourceMetadata.class));
    }

    @Test
    void handlesEmptyArrayResourceAsNull() {
        String json = """
                {"resource": []}""";

        AuthorizationServerProtectedResourceMetadata metadata =
                JsonMapperUtil.convertToObject(json, AuthorizationServerProtectedResourceMetadata.class);

        assertNull(metadata.getResource());
    }
}