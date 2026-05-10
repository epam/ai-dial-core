package com.epam.aidial.cli.template;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateComposerTest {

    @Test
    void cycleBetweenAandBdetectedNamesBoth() {
        Map<String, Object> templates = Map.of(
                "A", Map.of("extends", "B", "fields", Map.of("a", 1)),
                "B", Map.of("extends", "A", "fields", Map.of("b", 2))
        );
        TemplateComposer c = new TemplateComposer(templates);
        TemplateException e = assertThrows(TemplateException.class, () -> c.compose("A"));
        assertTrue(e.getMessage().contains("A"), e.getMessage());
        assertTrue(e.getMessage().contains("B"), e.getMessage());
    }

    @Test
    void selfLoopDetected() {
        Map<String, Object> templates = Map.of(
                "A", Map.of("extends", "A", "fields", Map.of("a", 1))
        );
        TemplateComposer c = new TemplateComposer(templates);
        TemplateException e = assertThrows(TemplateException.class, () -> c.compose("A"));
        assertTrue(e.getMessage().contains("A"), e.getMessage());
    }

    @Test
    void deepMergeMergesObjectsRecursively() {
        Map<String, Object> templates = Map.of(
                "base", Map.of("fields", Map.of(
                        "endpoint", "http://base",
                        "features", Map.of("a", true, "b", true))),
                "child", Map.of("extends", "base", "fields", Map.of(
                        "features", Map.of("b", false, "c", true)))
        );
        ObjectNode out = new TemplateComposer(templates).compose("child");
        assertEquals("http://base", out.get("endpoint").asText());
        assertEquals(true, out.get("features").get("a").asBoolean());
        assertEquals(false, out.get("features").get("b").asBoolean());
        assertEquals(true, out.get("features").get("c").asBoolean());
    }

    @Test
    void mixinSelfIncludeDetected() {
        // A includes itself — must throw, not StackOverflowError.
        Map<String, Object> templates = Map.of(
                "A", Map.of("includes", List.of("A"), "fields", Map.of("a", 1))
        );
        TemplateComposer c = new TemplateComposer(templates);
        TemplateException e = assertThrows(TemplateException.class, () -> c.compose("A"));
        assertTrue(e.getMessage().contains("A"), e.getMessage());
    }

    @Test
    void mixinCyclesBackToParentDetected() {
        // A includes B; B includes A — must throw with both names mentioned.
        Map<String, Object> templates = Map.of(
                "A", Map.of("includes", List.of("B"), "fields", Map.of("a", 1)),
                "B", Map.of("includes", List.of("A"), "fields", Map.of("b", 2))
        );
        TemplateComposer c = new TemplateComposer(templates);
        TemplateException e = assertThrows(TemplateException.class, () -> c.compose("A"));
        assertTrue(e.getMessage().contains("A"), e.getMessage());
        assertTrue(e.getMessage().contains("B"), e.getMessage());
    }

    @Test
    void arraysAreReplacedNotConcatenated() {
        Map<String, Object> templates = Map.of(
                "base", Map.of("fields", Map.of("xs", List.of(1, 2, 3))),
                "child", Map.of("extends", "base", "fields", Map.of("xs", List.of(9)))
        );
        ObjectNode out = new TemplateComposer(templates).compose("child");
        assertEquals(1, out.get("xs").size());
        assertEquals(9, out.get("xs").get(0).asInt());
    }
}
