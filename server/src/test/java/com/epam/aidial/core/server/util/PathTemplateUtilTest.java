package com.epam.aidial.core.server.util;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PathTemplateUtilTest {

    @Test
    void rendersVariables() {
        assertEquals("/openai/deployments/foo-bar/v1/chat/completions",
                PathTemplateUtil.render("/openai/deployments/{id}/v1/chat/completions", Map.of("id", "foo-bar")));
        assertEquals("/v1/responses/1234",
                PathTemplateUtil.render("/v1/responses/{id}", Map.of("id", "1234")));
    }

    @Test
    void rendersLiteralPathWithoutVariables() {
        assertEquals("/v1/messages", PathTemplateUtil.render("/v1/messages", Map.of()));
    }

    @Test
    void doubledBracesRenderLiteralBraces() {
        // the spec's own example: a doubled opening brace does not start a variable
        assertEquals("/v1/re{ponses/1234",
                PathTemplateUtil.render("/v1/re{{ponses/{id}", Map.of("id", "1234")));
        assertEquals("/literal/{id}",
                PathTemplateUtil.render("/literal/{{id}}", Map.of()));
    }

    @Test
    void unknownVariableThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> PathTemplateUtil.render("/v1/{nope}", Map.of("id", "1234")));
    }

    @Test
    void unmatchedBracesThrow() {
        assertThrows(IllegalArgumentException.class, () -> PathTemplateUtil.render("/v1/{id", Map.of("id", "1")));
        assertThrows(IllegalArgumentException.class, () -> PathTemplateUtil.render("/v1/id}", Map.of("id", "1")));
    }

    @Test
    void collectsReferencedVariables() {
        assertEquals(Set.of("id", "overrideName"),
                PathTemplateUtil.collectVariables("/d/{overrideName}/r/{id}"));
        assertEquals(Set.of(), PathTemplateUtil.collectVariables("/v1/messages"));
        assertThrows(IllegalArgumentException.class, () -> PathTemplateUtil.collectVariables("/v1/{id"));
    }
}
