package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.LocalizedValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoredAliasesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NAME = "assistant-v1";

    @Test
    void keepsTheTargetAndTheDisplayFieldsInEitherSpelling() {
        Alias alias = StoredAliases.fromRequest(json("""
                {"present_as": "model", "display_name": "Assistant", "description": "General",
                 "iconUrl": "http://icon", "user_roles": ["default-user"], "descriptionKeywords": ["chat"], "target": {"path": "models/gpt-4"},
                 "name": "assistant-v1", "status": "valid"}
                """), NAME);

        assertEquals(AliasPresentation.MODEL, alias.getPresentAs());
        assertEquals(LocalizedValue.of("Assistant"), alias.getDisplayName());
        assertEquals("models/gpt-4", alias.getTarget().getPath());
        assertEquals(List.of("chat"), alias.getDescriptionKeywords());
        assertNull(alias.getName());
        assertEquals(Set.of("default-user"), alias.getUserRoles());
    }

    @Test
    void refusesFieldsStoredAliasesDoNotKeep() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> StoredAliases.fromRequest(json("""
                {"target": {"path": "models/gpt-4"}, "features": {}, "endpoint": "http://x"}
                """), NAME));
        assertTrue(error.getMessage().contains("features, endpoint"), error.getMessage());

        assertThrows(IllegalArgumentException.class, () -> StoredAliases.fromRequest(json("[]"), NAME));
        assertThrows(IllegalArgumentException.class, () -> StoredAliases.fromRequest(json("{\"target\": {\"other\": 1}}"), NAME));
    }

    @Test
    void refusesNameOtherThanThePathName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> StoredAliases.fromRequest(
                json("{\"name\": \"other-v1\", \"target\": {\"path\": \"models/gpt-4\"}}"), NAME));
        assertTrue(error.getMessage().contains("other-v1"), error.getMessage());
    }

    @Test
    void blobHasOnlyTheFieldsThatAreSet() {
        Alias alias = StoredAliases.fromRequest(json("{\"displayName\": \"Assistant\", \"target\": {\"path\": \"models/gpt-4\"}}"), NAME);

        assertEquals(json("{\"target\": {\"path\": \"models/gpt-4\"}, \"displayName\": \"Assistant\"}"), StoredAliases.toJson(alias));
    }

    @Test
    void urlSafeNamesAreTheOnesTheEncodingLeavesAlone() {
        assertTrue(StoredAliases.isUrlSafeName("pub-v1.2_x"));
        assertFalse(StoredAliases.isUrlSafeName("pub@v1:2"));
        assertFalse(StoredAliases.isUrlSafeName("x[1]"));
        assertFalse(StoredAliases.isUrlSafeName("x%"));
        assertFalse(StoredAliases.isUrlSafeName("x y"));
    }

    @Test
    void parseKeepsTheReasonsCallsIgnoreBlob() {
        assertEquals("models/gpt-4", StoredAliases.parse("{\"target\":{\"path\":\"models/gpt-4\"}}").alias().getTarget().getPath());

        StoredAliases.Stored noTarget = StoredAliases.parse("{\"displayName\":\"x\"}");
        assertNull(noTarget.alias());
        assertEquals("target.path", noTarget.warnings().getFirst().getField());

        StoredAliases.Stored notJson = StoredAliases.parse("{");
        assertNull(notJson.alias());
        assertNull(notJson.body());
    }

    @SneakyThrows
    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @Test
    void targetMustBeConfigDeploymentOrPublicResource() {
        assertNotNull(StoredAliases.parse("{\"target\":{\"path\":\"models/gpt-4\"}}").alias());
        assertNotNull(StoredAliases.parse("{\"target\":{\"path\":\"applications/public/folder/app\"}}").alias());
        assertNotNull(StoredAliases.parse("{\"target\":{\"path\":\"toolsets/public/search\"}}").alias());

        StoredAliases.Stored userBucket = StoredAliases.parse("{\"target\":{\"path\":\"applications/3R4ac3QT6AZjujjrzHhd8M/app\"}}");
        assertNull(userBucket.alias());
        assertTrue(userBucket.warnings().getFirst().getMessage().startsWith("The target of a stored alias must be"),
                userBucket.warnings().toString());
        assertNull(StoredAliases.parse("{\"target\":{\"path\":\"toolsets/publicity/search\"}}").alias());
    }
}
