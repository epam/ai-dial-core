package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.LocalizedValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
    void keepsDefaultHeadersInEitherSpelling() {
        Alias model = StoredAliases.fromRequest(json("""
                {"defaultHeaders": {"X-Team": "b"}, "target": {"path": "models/gpt-4"}}
                """), NAME);
        assertEquals(Map.of("X-Team", "b"), model.getDefaultHeaders());

        Alias application = StoredAliases.fromRequest(json("""
                {"default_headers": {"X-Team": "b"}, "target": {"path": "applications/public/app"}}
                """), NAME);
        assertEquals(Map.of("X-Team", "b"), application.getDefaultHeaders());
    }

    @Test
    void nullDefaultHeadersReadAsNone() {
        Alias put = StoredAliases.fromRequest(json("{\"defaultHeaders\": null, \"target\": {\"path\": \"models/gpt-4\"}}"), NAME);
        assertEquals(Map.of(), put.getDefaultHeaders());
        assertFalse(StoredAliases.toJson(put).has("defaultHeaders"));

        Alias blob = StoredAliases.parse("{\"defaultHeaders\": null, \"target\": {\"path\": \"models/gpt-4\"}}", true).alias();
        assertEquals(Map.of(), blob.getDefaultHeaders());
        assertFalse(StoredAliases.toJson(blob).has("defaultHeaders"));

        // a field a stored alias does not keep is refused even when it is null
        assertThrows(IllegalArgumentException.class, () -> StoredAliases.fromRequest(
                json("{\"target\": {\"path\": \"models/gpt-4\"}, \"endpoint\": null}"), NAME));
    }

    @Test
    void defaultHeadersMustBeHeaderNamesToValues() {
        assertThrows(IllegalArgumentException.class, () -> StoredAliases.fromRequest(
                json("{\"defaultHeaders\": [\"X-Team\"], \"target\": {\"path\": \"models/gpt-4\"}}"), NAME));
    }

    @Test
    void toolsetAliasRefusesDefaultHeaders() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> StoredAliases.fromRequest(
                json("{\"defaultHeaders\": {\"X-Team\": \"a\"}, \"target\": {\"path\": \"toolsets/search\"}}"), NAME));
        assertTrue(error.getMessage().contains("toolset"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> StoredAliases.fromRequest(json("""
                {"presentAs": "toolset", "defaultHeaders": {"X-Team": "a"}, "target": {"path": "applications/mcp-app"}}
                """), NAME));

        Alias empty = StoredAliases.fromRequest(json("{\"defaultHeaders\": {}, \"target\": {\"path\": \"toolsets/search\"}}"), NAME);
        assertFalse(StoredAliases.toJson(empty).has("defaultHeaders"));
    }

    @Test
    void blobHasDefaultHeadersAfterUserRolesUnlessEmptyOrToolset() {
        Alias alias = StoredAliases.fromRequest(json("""
                {"displayName": "A", "defaultHeaders": {"X-Team": "b"}, "userRoles": ["default-user"], "target": {"path": "models/gpt-4"}}
                """), NAME);
        assertEquals("{\"target\":{\"path\":\"models/gpt-4\"},\"userRoles\":[\"default-user\"],\"defaultHeaders\":{\"X-Team\":\"b\"},\"displayName\":\"A\"}",
                StoredAliases.toJson(alias).toString());

        Alias empty = StoredAliases.fromRequest(json("{\"defaultHeaders\": {}, \"target\": {\"path\": \"models/gpt-4\"}}"), NAME);
        assertFalse(StoredAliases.toJson(empty).has("defaultHeaders"));

        // a toolset blob written around the API: calls never send its headers, and GET and the platform reload drop them
        Alias toolset = StoredAliases.parse("{\"defaultHeaders\": {\"X-Team\": \"b\"}, \"target\": {\"path\": \"toolsets/search\"}}", true).alias();
        assertNotNull(toolset);
        assertFalse(StoredAliases.toJson(toolset).has("defaultHeaders"));
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
        assertEquals("models/gpt-4", StoredAliases.parse("{\"target\":{\"path\":\"models/gpt-4\"}}", true).alias().getTarget().getPath());

        StoredAliases.Stored noTarget = StoredAliases.parse("{\"displayName\":\"x\"}", true);
        assertNull(noTarget.alias());
        assertEquals("target.path", noTarget.warnings().getFirst().getField());

        StoredAliases.Stored notJson = StoredAliases.parse("{", true);
        assertNull(notJson.alias());
        assertNull(notJson.body());
    }

    @SneakyThrows
    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @Test
    void targetMustBeConfigDeploymentOrPublicOrPlatformResource() {
        assertNotNull(StoredAliases.parse("{\"target\":{\"path\":\"models/gpt-4\"}}", true).alias());
        assertNotNull(StoredAliases.parse("{\"target\":{\"path\":\"applications/public/folder/app\"}}", true).alias());
        assertNotNull(StoredAliases.parse("{\"target\":{\"path\":\"toolsets/public/search\"}}", true).alias());
        assertNotNull(StoredAliases.parse("{\"target\":{\"path\":\"applications/platform/my-app\"}}", true).alias());
        assertNotNull(StoredAliases.parse("{\"target\":{\"path\":\"toolsets/platform/my-tools\"}}", true).alias());

        StoredAliases.Stored userBucket = StoredAliases.parse("{\"target\":{\"path\":\"applications/3R4ac3QT6AZjujjrzHhd8M/app\"}}", true);
        assertNull(userBucket.alias());
        assertTrue(userBucket.warnings().getFirst().getMessage().startsWith("The target of a stored alias must be"),
                userBucket.warnings().toString());
        assertNull(StoredAliases.parse("{\"target\":{\"path\":\"toolsets/publicity/search\"}}", true).alias());
    }

    @Test
    void onlyPublicAliasRefusesRouterTarget() {
        String router = "{\"presentAs\":\"model\",\"target\":{\"path\":\"routers/r1\"}}";
        StoredAliases.Stored publicAlias = StoredAliases.parse(router, true);
        assertNull(publicAlias.alias());
        assertEquals("target.path", publicAlias.warnings().getFirst().getField());
        assertNotNull(StoredAliases.parse(router, false).alias());
        String routerType = "{\"presentAs\":\"router\",\"target\":{\"path\":\"routers/r1\"}}";
        assertNull(StoredAliases.parse(routerType, true).alias());
        assertNotNull(StoredAliases.parse(routerType, false).alias());
        assertNull(StoredAliases.parse("{\"presentAs\":\"router\",\"target\":{\"path\":\"applications/public/app\"}}", true).alias());
    }
}
