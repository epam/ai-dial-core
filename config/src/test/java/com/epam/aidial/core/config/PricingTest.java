package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deserializesFlatPromptAndCompletionRates() throws Exception {
        String json = """
                {
                  "unit": "token",
                  "prompt": "0.56",
                  "completion": "0.67"
                }
                """;
        Pricing pricing = MAPPER.readValue(json, Pricing.class);
        assertEquals("token", pricing.getUnit());
        assertTrue(pricing.getPrompt().isLeaf());
        assertEquals("0.56", pricing.getPrompt().getRate());
        assertTrue(pricing.getCompletion().isLeaf());
        assertEquals("0.67", pricing.getCompletion().getRate());
    }

    @Test
    void deserializesDecisionTreePromptAndCompletionRates() throws Exception {
        String json = """
                {
                  "unit": "token",
                  "prompt": {
                    "test": { "field": "promptTokens", "operator": ">", "value": 272000 },
                    "ifTrue": "0.000004",
                    "ifFalse": "0.000002"
                  },
                  "completion": {
                    "test": { "field": "promptTokens", "operator": ">", "value": 272000 },
                    "ifTrue": "0.000018",
                    "ifFalse": "0.000012"
                  },
                  "cacheRead": {
                    "test": { "field": "promptTokens", "operator": ">", "value": 272000 },
                    "ifTrue": "0.0000004",
                    "ifFalse": "0.0000002"
                  },
                  "cacheWrite": {
                    "test": { "field": "promptTokens", "operator": ">", "value": 272000 },
                    "ifTrue": "0.000005",
                    "ifFalse": "0.0000025"
                  }
                }
                """;
        Pricing pricing = MAPPER.readValue(json, Pricing.class);

        assertFalse(pricing.getPrompt().isLeaf());
        assertEquals("0.000004", pricing.getPrompt().getIfTrue().getRate());
        assertEquals("0.000002", pricing.getPrompt().getIfFalse().getRate());

        assertFalse(pricing.getCompletion().isLeaf());
        assertEquals("0.000018", pricing.getCompletion().getIfTrue().getRate());
        assertEquals("0.000012", pricing.getCompletion().getIfFalse().getRate());

        assertFalse(pricing.getCacheRead().isLeaf());
        assertFalse(pricing.getCacheWrite().isLeaf());
    }
}
