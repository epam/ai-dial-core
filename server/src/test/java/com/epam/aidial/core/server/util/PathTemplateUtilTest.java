package com.epam.aidial.core.server.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathTemplateUtilTest {

    @Test
    void rendersBothTokens() {
        assertEquals("/openai/deployments/tst-name/v1/foo-bar/chat",
                PathTemplateUtil.render("/openai/deployments/{overrideName}/v1/{id}/chat", "tst-name", "foo-bar"));
        assertEquals("/v1/responses/1234", PathTemplateUtil.render("/v1/responses/{id}", "unused", "1234"));
    }

    @Test
    void rendersLiteralPathUntouched() {
        assertEquals("/v1/messages", PathTemplateUtil.render("/v1/messages", "name", null));
    }

    @Test
    void replacesEveryOccurrence() {
        assertEquals("/1234/x/1234", PathTemplateUtil.render("/{id}/x/{id}", "name", "1234"));
    }

    @Test
    void anythingElsePassesThroughLiterally() {
        // only the exact tokens mean anything; near-misses are plain path text
        assertEquals("/v1/{Id}/{deployment}/{id", PathTemplateUtil.render("/v1/{Id}/{deployment}/{id", "name", "1234"));
    }

    @Test
    void nullIdLeavesTheIdTokenAlone() {
        assertEquals("/v1/{id}", PathTemplateUtil.render("/v1/{id}", "name", null));
    }

    @Test
    void substitutedIdIsNeverRescanned() {
        // {id} goes last, so token-shaped text inside the id value is not substituted again
        assertEquals("/v1/{overrideName}", PathTemplateUtil.render("/v1/{id}", "name", "{overrideName}"));
    }

    @Test
    void strayBracesAreDetected() {
        assertFalse(PathTemplateUtil.hasStrayBraces("/v1/chat/completions"));
        assertFalse(PathTemplateUtil.hasStrayBraces("/d/{overrideName}/r/{id}"));
        assertTrue(PathTemplateUtil.hasStrayBraces("/v1/{Id}"));
        assertTrue(PathTemplateUtil.hasStrayBraces("/v1/{nope}"));
        assertTrue(PathTemplateUtil.hasStrayBraces("/v1/{id"));
        assertTrue(PathTemplateUtil.hasStrayBraces("/v1/id}"));
        // literal braces are not expressible in override paths: doubling is no longer an escape
        assertTrue(PathTemplateUtil.hasStrayBraces("/v1/re{{ponses/{id}"));
        // token-shaped text assembled around another token still counts as stray
        assertTrue(PathTemplateUtil.hasStrayBraces("{id{overrideName}}"));
    }

    @Test
    void referencesIdMatchesTheExactTokenOnly() {
        assertTrue(PathTemplateUtil.referencesId("/v1/{id}/messages"));
        assertFalse(PathTemplateUtil.referencesId("/v1/{Id}/messages"));
        assertFalse(PathTemplateUtil.referencesId("/v1/messages"));
    }
}
