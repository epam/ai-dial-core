package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.TokenLimits;
import com.epam.aidial.core.server.data.anthropic.AnthropicModelData;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnthropicModelControllerTest {

    @Test
    void createModel_mapsIdAndDisplayNameAndCreatedAt() {
        Model model = new Model();
        model.setName("claude-3-5-sonnet");
        model.setDisplayName(LocalizedValue.of("Claude 3.5 Sonnet"));
        model.setCreatedAt(1_700_000_000L);

        AnthropicModelData data = AnthropicModelController.createModel(model, "en");

        assertEquals("claude-3-5-sonnet", data.getId());
        assertEquals("model", data.getType());
        assertEquals("Claude 3.5 Sonnet", data.getDisplayName());
        assertEquals(Instant.ofEpochSecond(1_700_000_000L).toString(), data.getCreatedAt());
    }

    @Test
    void createModel_fallsBackToNameAndDefaultCreatedAtWhenUnset() {
        Model model = new Model();
        model.setName("claude-3-haiku");

        AnthropicModelData data = AnthropicModelController.createModel(model, "en");

        assertEquals("claude-3-haiku", data.getDisplayName());
        assertEquals(Instant.ofEpochSecond(1672534800L).toString(), data.getCreatedAt());
    }

    @Test
    void createModel_mapsMaxInputTokensAndMaxTokensFromLimits() {
        Model model = new Model();
        model.setName("claude-limits");
        TokenLimits limits = new TokenLimits();
        limits.setMaxPromptTokens(100_000);
        limits.setMaxCompletionTokens(8192);
        model.setLimits(limits);

        AnthropicModelData data = AnthropicModelController.createModel(model, "en");

        assertEquals(100_000, data.getMaxInputTokens());
        assertEquals(8192, data.getMaxTokens());
    }

    @Test
    void createModel_omitsMaxInputTokensAndMaxTokensWhenLimitsUnset() {
        Model model = new Model();
        model.setName("claude-no-limits");

        AnthropicModelData data = AnthropicModelController.createModel(model, "en");

        assertNull(data.getMaxInputTokens());
        assertNull(data.getMaxTokens());
    }
}
