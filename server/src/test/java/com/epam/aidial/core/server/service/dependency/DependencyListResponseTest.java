package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The answer is one entry per root, each with a flat list of its dependencies, and nothing else. */
public class DependencyListResponseTest {

    private static List<String> fieldsOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void testRootEntryHoldsTheFourFieldsAndTheDependenciesAndAnItemHoldsTheFour() {
        DependencyListResponse response = new DependencyListResponse(List.of(new DependencyRootEntry(
                new DependencyResource("application", "applications/public/team/a", "a", "public"),
                List.of(new DependencyResource("model", "models/platform/gpt-4o", "gpt-4o", "platform")))));

        JsonNode tree = ProxyUtil.MAPPER.valueToTree(response);

        assertEquals(List.of("roots"), fieldsOf(tree));
        JsonNode root = tree.get("roots").get(0);
        assertEquals(List.of("type", "url", "name", "origin", "dependencies"), fieldsOf(root));
        JsonNode item = root.get("dependencies").get(0);
        assertEquals(List.of("type", "url", "name", "origin"), fieldsOf(item));
        assertEquals("application", root.get("type").asText());
        assertEquals("gpt-4o", item.get("name").asText());
    }

    @Test
    void testNullUrlIsSerializedAndAnEmptyListIsAnEmptyArray() {
        DependencyListResponse response = new DependencyListResponse(List.of(new DependencyRootEntry(
                new DependencyResource("application", "applications/platform/a", "a", "platform"),
                List.of(new DependencyResource("model", null, "gpt-4o-mini", "file")))));
        DependencyListResponse empty = new DependencyListResponse(List.of(new DependencyRootEntry(
                new DependencyResource("application", "applications/platform/b", "b", "platform"), List.of())));

        JsonNode item = ProxyUtil.MAPPER.valueToTree(response).get("roots").get(0).get("dependencies").get(0);
        JsonNode emptyRoot = ProxyUtil.MAPPER.valueToTree(empty).get("roots").get(0);

        assertTrue(item.has("url") && item.get("url").isNull(), "an entity of the file configuration has url null, not an absent url");
        assertTrue(emptyRoot.get("dependencies").isArray());
        assertEquals(0, emptyRoot.get("dependencies").size());
    }
}
