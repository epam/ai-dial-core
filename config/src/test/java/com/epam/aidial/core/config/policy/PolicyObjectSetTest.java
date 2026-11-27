package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyObjectSetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PolicyObjectSet read(String json) throws Exception {
        return MAPPER.readValue(json, PolicyObjectSet.class);
    }

    private static String write(PolicyObjectSet set) throws Exception {
        return MAPPER.writeValueAsString(set);
    }

    private static PolicyPackage policyPackage(String... deployments) {
        PolicyPackage policyPackage = new PolicyPackage();
        policyPackage.setDeployments(List.of(deployments));
        return policyPackage;
    }

    @Test
    void testSinglePackageReferenceReadsBackAsString() throws Exception {
        PolicyObjectSet set = read("\"@engineering\"");

        assertEquals("\"@engineering\"", write(set));
        assertEquals(Set.of("engineering"), set.packageNames());
    }

    @Test
    void testMixedListReadsBackSorted() throws Exception {
        PolicyObjectSet set = read("[\"gpt-6-luna\", \"@engineering\"]");

        assertEquals("[\"@engineering\",\"gpt-6-luna\"]", write(set));
        assertEquals(Set.of("engineering"), set.packageNames());
    }

    @Test
    void testAllAndPlainNamesKeepTheirForm() throws Exception {
        assertEquals("\"*\"", write(read("\"*\"")));
        assertEquals("[\"a\",\"b\"]", write(read("[\"b\", \"a\"]")));
        assertTrue(read("\"*\"").packageNames().isEmpty());
    }

    @Test
    void testMalformedObjectsAreRejected() {
        for (String json : List.of("\"gpt-4\"", "\"@\"", "\"@a/b\"", "\"@@a\"", "[]", "[\"@engineering\", \"*\"]", "[\"@\"]", "[\"\"]", "42")) {
            assertThrows(ValueInstantiationException.class, () -> read(json), json);
        }
    }

    @Test
    void testExpandReplacesReferencesByMembers() throws Exception {
        PolicyObjectSet expanded = read("[\"@engineering\", \"gpt-6-luna\", \"@missing\"]")
                .expand(Map.of("engineering", policyPackage("weak", "strong")));

        assertTrue(expanded.contains("weak"));
        assertTrue(expanded.contains("strong"));
        assertTrue(expanded.contains("gpt-6-luna"));
        assertFalse(expanded.contains("@engineering"));
        assertTrue(expanded.packageNames().isEmpty());
    }

    @Test
    void testExpandOfAllIsAll() {
        assertTrue(PolicyObjectSet.all().expand(Map.of()).isAll());
    }

    @Test
    void testExpandOfMissingPackageCoversNothing() throws Exception {
        PolicyObjectSet expanded = read("\"@missing\"").expand(Map.of());

        assertFalse(expanded.isAll());
        assertEquals(-1, expanded.indexOfFirstCovered(List.of("strong", "m")));
    }

    @Test
    void testIndexOfFirstCovered() {
        List<String> chain = List.of("strong", "r1", "claude-opus-vertex", "m");

        assertEquals(0, PolicyObjectSet.all().indexOfFirstCovered(chain));
        assertEquals(0, PolicyObjectSet.of(List.of("strong", "m")).indexOfFirstCovered(chain));
        assertEquals(3, PolicyObjectSet.of(List.of("m")).indexOfFirstCovered(chain));
        assertEquals(-1, PolicyObjectSet.of(List.of("hi-end")).indexOfFirstCovered(chain));
    }
}
