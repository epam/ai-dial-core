package com.epam.aidial.core.server.pricing;

import com.epam.aidial.core.config.Condition;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Operator;
import com.epam.aidial.core.config.PricingRate;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingRateEvaluatorTest {

    private static PricingRate leaf(String rate) {
        PricingRate pricingRate = new PricingRate();
        pricingRate.setRate(rate);
        return pricingRate;
    }

    private static PricingRate node(String field, Operator operator, Object value, PricingRate ifTrue, PricingRate ifFalse) {
        Condition condition = new Condition();
        condition.setField(field);
        condition.setOperator(operator);
        condition.setValue(value);
        PricingRate pricingRate = new PricingRate();
        pricingRate.setTest(condition);
        pricingRate.setIfTrue(ifTrue);
        pricingRate.setIfFalse(ifFalse);
        return pricingRate;
    }

    private static UsageEvalContext contextFor(String json) throws JsonProcessingException {
        JsonNode root = ProxyUtil.MAPPER.readTree(json);
        return UsageEvalContext.build(InterfaceType.ANTHROPIC_MESSAGES, root);
    }

    @Test
    void leafResolvesToItsOwnRate() throws JsonProcessingException {
        UsageEvalContext ctx = contextFor("{}");
        assertEquals(Optional.of("0.1"), PricingRateEvaluator.evaluate(leaf("0.1"), ctx));
    }

    @Test
    void anthropicTtlContextTierMatrixResolvesToTheRightLeaf() throws JsonProcessingException {
        // Mirrors the design doc's §3 worked example: promptTokens > 200000 and ttl == "1h" -> 0.000012
        PricingRate tree = node("promptTokens", Operator.GT, 200000,
                node("ttl", Operator.EQ, "1h", leaf("0.000012"), leaf("0.0000075")),
                node("ttl", Operator.EQ, "1h", leaf("0.000006"), leaf("0.00000375")));

        UsageEvalContext ctx = contextFor("""
                {
                  "usage": {
                    "input_tokens": 249500,
                    "cache_read_input_tokens": 400,
                    "cache_creation_input_tokens": 100,
                    "cache_creation": { "ephemeral_5m_input_tokens": 0, "ephemeral_1h_input_tokens": 100 }
                  }
                }
                """);

        assertEquals(Optional.of("0.000012"), PricingRateEvaluator.evaluate(tree, ctx));
    }

    @Test
    void missingFieldIsNonMatchAndFallsThroughToIfFalse() throws JsonProcessingException {
        PricingRate tree = node("serviceTier", Operator.EQ, "flex", leaf("0.1"), leaf("0.2"));
        UsageEvalContext ctx = contextFor("{ \"usage\": {} }");

        assertEquals(Optional.of("0.2"), PricingRateEvaluator.evaluate(tree, ctx));
    }

    @Test
    void omittedBranchBottomsOutToEmptyForCallerToFallBackToPromptRate() throws JsonProcessingException {
        PricingRate tree = node("serviceTier", Operator.EQ, "flex", leaf("0.1"), null);
        UsageEvalContext ctx = contextFor("{ \"usage\": {} }");

        assertTrue(PricingRateEvaluator.evaluate(tree, ctx).isEmpty());
    }

    @Test
    void omittedBranchMidTreeAlsoFallsBackToEmpty() throws JsonProcessingException {
        PricingRate inner = node("ttl", Operator.EQ, "1h", leaf("0.5"), null);
        PricingRate tree = node("promptTokens", Operator.GT, 0, inner, leaf("0.9"));
        UsageEvalContext ctx = contextFor("{ \"usage\": { \"input_tokens\": 1 } }");

        assertTrue(PricingRateEvaluator.evaluate(tree, ctx).isEmpty());
    }

    @Test
    void orderingOperatorAgainstJsonPathStringValueIsNonMatch() throws JsonProcessingException {
        Condition condition = new Condition();
        condition.setField("$.usage.service_tier");
        condition.setOperator(Operator.GT);
        condition.setValue(1);
        PricingRate tree = new PricingRate();
        tree.setTest(condition);
        tree.setIfTrue(leaf("0.1"));
        tree.setIfFalse(leaf("0.2"));

        UsageEvalContext ctx = contextFor("{ \"usage\": { \"service_tier\": \"standard\" } }");

        assertEquals(Optional.of("0.2"), PricingRateEvaluator.evaluate(tree, ctx));
    }

    @Test
    void jsonPathConditionMatchesAgainstUsageRoot() throws JsonProcessingException {
        Condition condition = new Condition();
        condition.setField("$.usage.server_tool_use.web_search_requests");
        condition.setOperator(Operator.GT);
        condition.setValue(0);
        PricingRate tree = new PricingRate();
        tree.setTest(condition);
        tree.setIfTrue(leaf("0.1"));
        tree.setIfFalse(leaf("0.2"));

        UsageEvalContext ctx = contextFor("{ \"usage\": { \"server_tool_use\": { \"web_search_requests\": 3 } } }");

        assertEquals(Optional.of("0.1"), PricingRateEvaluator.evaluate(tree, ctx));
    }
}
