package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Condition;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Operator;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.PricingRate;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings("checkstyle:LineLength")
public class ModelCostCalculatorTest {

    private static PricingRate flatRate(String rate) {
        PricingRate pricingRate = new PricingRate();
        pricingRate.setRate(rate);
        return pricingRate;
    }

    @Test
    public void testCalculate_TokenCost() {
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("token");

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setPromptTokens(10);

        assertEquals(new BigDecimal("6.0"), ModelCostCalculator.calculateTokenCost(tokenUsage, pricing, InterfaceType.OPENAI_CHAT_COMPLETIONS, MissingNode.getInstance()));
    }

    @Test
    public void testCalculate_TokenCost_CacheRatesUnset_MatchesLegacyCost() {
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("token");

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setPromptTokens(10);
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCachedTokens(4);
        details.setCacheWriteTokens(2);
        tokenUsage.setPromptTokensDetails(details);

        assertEquals(new BigDecimal("6.0"), ModelCostCalculator.calculateTokenCost(tokenUsage, pricing, InterfaceType.OPENAI_CHAT_COMPLETIONS, MissingNode.getInstance()));
    }

    @Test
    public void testCalculate_TokenCost_WithCacheReadWriteRates() {
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setCacheRead(flatRate("0.01"));
        pricing.setCacheWrite(flatRate("0.02"));
        pricing.setUnit("token");

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setPromptTokens(10);
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCachedTokens(4);
        details.setCacheWriteTokens(2);
        tokenUsage.setPromptTokensDetails(details);

        assertEquals(new BigDecimal("5.48"), ModelCostCalculator.calculateTokenCost(tokenUsage, pricing, InterfaceType.OPENAI_CHAT_COMPLETIONS, MissingNode.getInstance()));
    }

    @Test
    public void testCalculate_TokenCost_ExplicitZeroCacheReadRate() {
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setCacheRead(flatRate("0"));
        pricing.setUnit("token");

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setPromptTokens(10);
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCachedTokens(4);
        tokenUsage.setPromptTokensDetails(details);

        assertEquals(new BigDecimal("5.6"), ModelCostCalculator.calculateTokenCost(tokenUsage, pricing, InterfaceType.OPENAI_CHAT_COMPLETIONS, MissingNode.getInstance()));
    }

    @Test
    public void testCalculate_LengthCost_Chat_StreamIsMissing_Success() {
        String response = """
                {
                   "choices": [
                     {
                       "index": 0,
                       "finish_reason": "stop",
                       "message": {
                         "role": "assistant",
                         "content": "A file is a named collection."
                       }
                     }
                   ],
                   "usage": {
                     "prompt_tokens": 4,
                     "completion_tokens": 343,
                     "total_tokens": 347
                   },
                   "id": "fd3be95a-c208-4dca-90cf-67e5082a4e5b",
                   "created": 1705319789,
                   "object": "chat.completion"
                 }
                """;

        String request = """
                {
                  "messages": [
                    {
                      "role": "system",
                      "content": ""
                    },
                    {
                      "role": "user",
                      "content": "How are you?"
                    }
                  ],
                  "max_tokens": 500,
                  "temperature": 1
                }
                """;

        assertEquals(new BigDecimal("13.0"), ModelCostCalculator.calculateCharCost(ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Body(Buffer.buffer(response)), "0.1", "0.5"));
    }

    @Test
    public void testCalculate_LengthCost_Chat_StreamIsFalse_Success() {
        String response = """
                {
                   "choices": [
                     {
                       "index": 0,
                       "finish_reason": "stop",
                       "message": {
                         "role": "assistant",
                         "content": "A file is a named collection."
                       }
                     }
                   ],
                   "usage": {
                     "prompt_tokens": 4,
                     "completion_tokens": 343,
                     "total_tokens": 347
                   },
                   "id": "fd3be95a-c208-4dca-90cf-67e5082a4e5b",
                   "created": 1705319789,
                   "object": "chat.completion"
                 }
                """;

        String request = """
                {
                  "messages": [
                    {
                      "role": "system",
                      "content": ""
                    },
                    {
                      "role": "user",
                      "content": "How are you?"
                    }
                  ],
                  "max_tokens": 500,
                  "temperature": 1,
                  "stream": false
                }
                """;

        assertEquals(new BigDecimal("13.0"), ModelCostCalculator.calculateCharCost(ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Body(Buffer.buffer(response)), "0.1", "0.5"));
    }

    /**
     * The non-streaming fast path: a caller that already parsed the response reads that tree directly,
     * with no re-serialize-then-reparse round trip - {@code ResponseSource.Tree} has no {@code Buffer} field
     * at all, so this branch cannot re-deserialize anything even in principle. Same fixture, same request, as
     * {@link #testCalculate_LengthCost_Chat_StreamIsFalse_Success}, so the result must be identical.
     */
    @Test
    public void testCalculate_LengthCost_Chat_TreeFastPath_MatchesBufferResult() throws Exception {
        String response = """
                {
                   "choices": [
                     {
                       "index": 0,
                       "finish_reason": "stop",
                       "message": {
                         "role": "assistant",
                         "content": "A file is a named collection."
                       }
                     }
                   ],
                   "usage": {
                     "prompt_tokens": 4,
                     "completion_tokens": 343,
                     "total_tokens": 347
                   },
                   "id": "fd3be95a-c208-4dca-90cf-67e5082a4e5b",
                   "created": 1705319789,
                   "object": "chat.completion"
                 }
                """;

        String request = """
                {
                  "messages": [
                    {
                      "role": "system",
                      "content": ""
                    },
                    {
                      "role": "user",
                      "content": "How are you?"
                    }
                  ],
                  "max_tokens": 500,
                  "temperature": 1,
                  "stream": false
                }
                """;

        JsonNode responseTree = ProxyUtil.MAPPER.readTree(response);

        assertEquals(new BigDecimal("13.0"), ModelCostCalculator.calculateCharCost(ModelType.CHAT, Buffer.buffer(request),
                new ModelCostCalculator.ResponseSource.Tree(responseTree), "0.1", "0.5"));
    }

    /**
     * The one case where the tree fast path cannot apply: the request claims {@code "stream": true} but the
     * response nonetheless parsed as a single, well-formed JSON document (so the caller passed a {@code Tree}).
     * A {@code Tree} can never itself be SSE-framed text - {@code JsonUtil.tryParse} would have failed and
     * returned {@code MissingNode} for that - so this is strictly "the request asked to stream but the
     * response wasn't SSE after all", not a second representation competing with the first. The fallback
     * re-serializes and runs the exact pre-existing byte-scan, so it must fail exactly as that scan already
     * does on a normal response shape (whose {@code choices[0]} has no {@code delta}) - proving no behavior
     * changed for this pre-existing edge case, not just that the common case got faster.
     */
    @Test
    public void testCalculate_LengthCost_Chat_TreeStreamMismatch_FallsBackToOriginalByteScanBehavior() throws Exception {
        String response = """
                {
                   "choices": [
                     {
                       "index": 0,
                       "finish_reason": "stop",
                       "message": {
                         "role": "assistant",
                         "content": "A file is a named collection."
                       }
                     }
                   ]
                 }
                """;
        String request = """
                {
                  "messages": [
                    { "role": "user", "content": "How are you?" }
                  ],
                  "stream": true
                }
                """;

        assertThrows(RuntimeException.class, () -> ModelCostCalculator.calculateCharCost(
                ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Body(Buffer.buffer(response)),
                "0.1", "0.5"));

        JsonNode responseTree = ProxyUtil.MAPPER.readTree(response);
        assertThrows(RuntimeException.class, () -> ModelCostCalculator.calculateCharCost(
                ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Tree(responseTree),
                "0.1", "0.5"));
    }

    /**
     * Parity with the {@code Buffer} branch: a non-object top-level response makes the old code's
     * {@code (ObjectNode) readTree(...)} cast throw a {@code ClassCastException}, wrapped as a
     * {@code RuntimeException} by its {@code catch (Throwable e)}. The tree fast path must fail the same
     * way instead of silently reading {@code choices} as absent and returning {@code 0}.
     */
    @Test
    public void testCalculate_LengthCost_Chat_NonObjectResponse_TreeAndBufferBothThrow() throws Exception {
        String response = "[1,2,3]";
        String request = """
                {
                  "messages": [
                    { "role": "user", "content": "How are you?" }
                  ],
                  "stream": false
                }
                """;

        assertThrows(RuntimeException.class, () -> ModelCostCalculator.calculateCharCost(
                ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Body(Buffer.buffer(response)),
                "0.1", "0.5"));

        JsonNode responseTree = ProxyUtil.MAPPER.readTree(response);
        assertThrows(RuntimeException.class, () -> ModelCostCalculator.calculateCharCost(
                ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Tree(responseTree),
                "0.1", "0.5"));
    }

    /**
     * Parity with the {@code Buffer} branch: an object response whose {@code choices} array has no first
     * element makes the old code's {@code choices.get(0).get("message")} throw a
     * {@code NullPointerException}, wrapped as a {@code RuntimeException} by its {@code catch (Throwable e)}.
     * The tree fast path must wrap the same {@code NullPointerException} the same way, not let it propagate
     * raw - checked by exact exception class and cause, since a bare {@code NullPointerException} would
     * otherwise also satisfy an {@code assertThrows(RuntimeException.class, ...)} and hide the missing wrap.
     */
    @Test
    public void testCalculate_LengthCost_Chat_EmptyChoicesArray_TreeAndBufferBothThrow() throws Exception {
        String response = """
                { "choices": [] }
                """;
        String request = """
                {
                  "messages": [
                    { "role": "user", "content": "How are you?" }
                  ],
                  "stream": false
                }
                """;

        RuntimeException bufferException = assertThrows(RuntimeException.class, () -> ModelCostCalculator.calculateCharCost(
                ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Body(Buffer.buffer(response)),
                "0.1", "0.5"));
        assertEquals(RuntimeException.class, bufferException.getClass());
        assertInstanceOf(NullPointerException.class, bufferException.getCause());

        JsonNode responseTree = ProxyUtil.MAPPER.readTree(response);
        RuntimeException treeException = assertThrows(RuntimeException.class, () -> ModelCostCalculator.calculateCharCost(
                ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Tree(responseTree),
                "0.1", "0.5"));
        assertEquals(RuntimeException.class, treeException.getClass());
        assertInstanceOf(NullPointerException.class, treeException.getCause());
    }

    @Test
    public void testCalculate_LengthCost_Chat_StreamIsFalse_Error() {
        String response = """
                {"error": { "message": "message", "type": "type", "param": "param", "code": "code" } }
                """;

        String request = """
                {
                  "messages": [
                    {
                      "role": "system",
                      "content": ""
                    },
                    {
                      "role": "user",
                      "content": "How are you?"
                    }
                  ],
                  "max_tokens": 500,
                  "temperature": 1,
                  "stream": false
                }
                """;

        assertEquals(new BigDecimal("1.0"), ModelCostCalculator.calculateCharCost(ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Body(Buffer.buffer(response)), "0.1", "0.5"));
    }

    @Test
    public void testCalculate_LengthCost_Chat_StreamIsTrue_Success() {
        String response = """
                data:   {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant"}}],"usage":null}

                data:   {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"this"}}],"usage":null}

                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" is "}}],"usage":null}



                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"a text"}}],"usage":null}

                data: [DONE]


                """;

        String request = """
                {
                  "messages": [
                    {
                      "role": "system",
                      "content": ""
                    },
                    {
                      "role": "user",
                      "content": "How are you?"
                    }
                  ],
                  "max_tokens": 500,
                  "temperature": 1,
                  "stream": true
                }
                """;

        assertEquals(new BigDecimal("6.5"), ModelCostCalculator.calculateCharCost(ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Body(Buffer.buffer(response)), "0.1", "0.5"));
    }

    @Test
    public void testCalculate_LengthCost_Chat_StreamIsTrue_Error() {
        String response = """
                data:   {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant"}}],"usage":null}

                data:   {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"this"}}],"usage":null}

                data: {"error": { "message": "message", "type": "type", "param": "param", "code": "code" } }



                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"a text"}}],"usage":null}

                data: [DONE]


                """;

        String request = """
                {
                  "messages": [
                    {
                      "role": "system",
                      "content": ""
                    },
                    {
                      "role": "user",
                      "content": "How are you?"
                    }
                  ],
                  "max_tokens": 500,
                  "temperature": 1,
                  "stream": true
                }
                """;

        assertEquals(new BigDecimal("5.5"), ModelCostCalculator.calculateCharCost(ModelType.CHAT, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Body(Buffer.buffer(response)), "0.1", "0.5"));
    }

    @Test
    public void testCalculate_LengthCost_EmbeddingInputIsArray() {
        String response = """
                {}
                """;

        String request = """
                {
                  "input": ["text", "123"]
                }
                """;

        assertEquals(new BigDecimal("0.7"), ModelCostCalculator.calculateCharCost(ModelType.EMBEDDING, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Body(Buffer.buffer(response)), "0.1", "0.5"));
    }

    @Test
    public void testCalculate_LengthCost_EmbeddingInputIsString() {
        String response = """
                {}
                """;

        String request = """
                {
                  "input": "text"
                }
                """;

        assertEquals(new BigDecimal("0.4"), ModelCostCalculator.calculateCharCost(ModelType.EMBEDDING, Buffer.buffer(request), new ModelCostCalculator.ResponseSource.Body(Buffer.buffer(response)), "0.1", "0.5"));
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

    /** Design doc §3: Anthropic claude-sonnet-4-5-style TTL x context-tier matrix, passthrough mode. */
    @Test
    public void testCalculate_DecisionTree_AnthropicTtlContextTierMatrix() {
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.000003");
        pricing.setCompletion("0.000015");
        pricing.setCacheRead(node("promptTokens", Operator.GT, 200000, flatRate("0.0000006"), flatRate("0.0000003")));
        pricing.setCacheWrite(node("promptTokens", Operator.GT, 200000,
                node("ttl", Operator.EQ, "1h", flatRate("0.000012"), flatRate("0.0000075")),
                node("ttl", Operator.EQ, "1h", flatRate("0.000006"), flatRate("0.00000375"))));

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(250000);
        tokenUsage.setCompletionTokens(0);

        JsonNode responseTree = usageRoot("""
                {
                  "usage": {
                    "input_tokens": 249500,
                    "cache_read_input_tokens": 400,
                    "cache_creation_input_tokens": 100,
                    "cache_creation": { "ephemeral_5m_input_tokens": 0, "ephemeral_1h_input_tokens": 100 },
                    "service_tier": "standard"
                  }
                }
                """);

        BigDecimal cost = ModelCostCalculator.calculateTokenCost(tokenUsage, pricing, InterfaceType.ANTHROPIC_MESSAGES, responseTree);

        // base: (250000 - 400 - 100) * 0.000003
        // cacheRead: 400 * 0.0000006 (promptTokens>200000 -> the >200K leaf)
        // cacheWrite: 100 * 0.000012 (promptTokens>200000 && ttl==1h -> the above_1hr_above_200k leaf)
        BigDecimal expected = new BigDecimal("249500").multiply(new BigDecimal("0.000003"))
                .add(new BigDecimal("400").multiply(new BigDecimal("0.0000006")))
                .add(new BigDecimal("100").multiply(new BigDecimal("0.000012")));
        assertEquals(expected, cost);
    }

    /** Design doc §6: OpenAI gpt-5.6-style service-tier x context-tier matrix, passthrough mode. */
    @Test
    public void testCalculate_DecisionTree_OpenAiServiceTierContextTierMatrix() {
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.0000025");
        pricing.setCompletion("0.00001");
        pricing.setCacheWrite(node("promptTokens", Operator.GT, 272000,
                node("serviceTier", Operator.EQ, "flex", flatRate("0.00000625"), flatRate("0.0000125")),
                node("serviceTier", Operator.EQ, "flex", flatRate("0.000003125"),
                        node("serviceTier", Operator.EQ, "priority", flatRate("0.0000125"), flatRate("0.00000625")))));
        pricing.setCacheRead(node("promptTokens", Operator.GT, 272000,
                node("serviceTier", Operator.EQ, "flex", flatRate("0.0000005"), flatRate("0.000001")),
                node("serviceTier", Operator.EQ, "flex", flatRate("0.00000025"),
                        node("serviceTier", Operator.EQ, "priority", flatRate("0.000001"), flatRate("0.0000005")))));

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(150000);
        tokenUsage.setCompletionTokens(200);

        JsonNode responseTree = usageRoot("""
                {
                  "usage": {
                    "prompt_tokens": 150000,
                    "prompt_tokens_details": { "cached_tokens": 800, "cache_write_tokens": 50 },
                    "completion_tokens": 200
                  },
                  "service_tier": "flex"
                }
                """);

        BigDecimal cost = ModelCostCalculator.calculateTokenCost(tokenUsage, pricing, InterfaceType.OPENAI_CHAT_COMPLETIONS, responseTree);

        // base: (150000 - 800 - 50) * promptRate; completion: 200 * completionRate
        // cacheRead: 800 * 0.00000025 (<=272k, flex leaf); cacheWrite: 50 * 0.000003125 (<=272k, flex leaf)
        BigDecimal expected = new BigDecimal("149150").multiply(new BigDecimal("0.0000025"))
                .add(new BigDecimal("200").multiply(new BigDecimal("0.00001")))
                .add(new BigDecimal("800").multiply(new BigDecimal("0.00000025")))
                .add(new BigDecimal("50").multiply(new BigDecimal("0.000003125")));
        assertEquals(expected, cost);
    }

    /** Design doc §7a: missing discriminator (service_tier absent), tree still configured. */
    @Test
    public void testCalculate_DecisionTree_MissingDiscriminatorFallsBackToIfFalseLeaf() {
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.0000025");
        pricing.setCompletion("0.00001");
        pricing.setCacheWrite(node("promptTokens", Operator.GT, 272000,
                node("serviceTier", Operator.EQ, "flex", flatRate("0.00000625"), flatRate("0.0000125")),
                node("serviceTier", Operator.EQ, "flex", flatRate("0.000003125"),
                        node("serviceTier", Operator.EQ, "priority", flatRate("0.0000125"), flatRate("0.00000625")))));

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(150000);
        tokenUsage.setCompletionTokens(200);
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCacheWriteTokens(50);
        tokenUsage.setPromptTokensDetails(details);

        JsonNode responseTree = usageRoot("""
                {
                  "usage": {
                    "prompt_tokens": 150000,
                    "prompt_tokens_details": { "cached_tokens": 800, "cache_write_tokens": 50 },
                    "completion_tokens": 200
                  }
                }
                """);

        BigDecimal cost = ModelCostCalculator.calculateTokenCost(tokenUsage, pricing, InterfaceType.OPENAI_CHAT_COMPLETIONS, responseTree);

        // serviceTier is absent -> every serviceTier test is a non-match -> bottoms out at the
        // <=272k, not-flex, not-priority leaf: 0.00000625
        BigDecimal expected = new BigDecimal("150000").subtract(new BigDecimal("800")).subtract(new BigDecimal("50"))
                .multiply(new BigDecimal("0.0000025"))
                .add(new BigDecimal("200").multiply(new BigDecimal("0.00001")))
                .add(new BigDecimal("800").multiply(new BigDecimal("0.0000025"))) // cacheRead unset -> promptRate
                .add(new BigDecimal("50").multiply(new BigDecimal("0.00000625")));
        assertEquals(expected, cost);
    }

    /** Design doc §7b: no usable usage source at all (no custom_fields.upstream_usage, no native usage). */
    @Test
    public void testCalculate_DecisionTree_NoUsableUsageSourceFallsBackToPromptTokensDetails() {
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setCacheRead(node("serviceTier", Operator.EQ, "flex", flatRate("0.01"), flatRate("0.02")));

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(10);
        tokenUsage.setCompletionTokens(10);
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCachedTokens(4);
        tokenUsage.setPromptTokensDetails(details);

        BigDecimal cost = ModelCostCalculator.calculateTokenCost(tokenUsage, pricing, InterfaceType.OPENAI_CHAT_COMPLETIONS, MissingNode.getInstance());

        // no response tree at all -> counter falls back to PromptTokensDetails.cachedTokens=4,
        // rate resolution never matches serviceTier -> falls back to ifFalse=0.02
        BigDecimal expected = new BigDecimal("6").multiply(new BigDecimal("0.1"))
                .add(new BigDecimal("10").multiply(new BigDecimal("0.5")))
                .add(new BigDecimal("4").multiply(new BigDecimal("0.02")));
        assertEquals(expected, cost);
    }

    /** Design doc §4: translation-mode parity - same price as the passthrough §3 case for a byte-identical envelope. */
    @Test
    public void testCalculate_DecisionTree_TranslationModeParityWithPassthrough() {
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.000003");
        pricing.setCompletion("0.000015");
        pricing.setCacheRead(node("promptTokens", Operator.GT, 200000, flatRate("0.0000006"), flatRate("0.0000003")));
        pricing.setCacheWrite(node("promptTokens", Operator.GT, 200000,
                node("ttl", Operator.EQ, "1h", flatRate("0.000012"), flatRate("0.0000075")),
                node("ttl", Operator.EQ, "1h", flatRate("0.000006"), flatRate("0.00000375"))));

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(250000);
        tokenUsage.setCompletionTokens(0);

        JsonNode responseTree = usageRoot("""
                {
                  "usage": { "prompt_tokens": 250000, "prompt_tokens_details": { "cached_tokens": 400, "cache_write_tokens": 100 } },
                  "custom_fields": {
                    "upstream_usage": {
                      "interface": "anthropicMessages",
                      "usage": {
                        "input_tokens": 249500,
                        "cache_read_input_tokens": 400,
                        "cache_creation_input_tokens": 100,
                        "cache_creation": { "ephemeral_5m_input_tokens": 0, "ephemeral_1h_input_tokens": 100 },
                        "service_tier": "standard"
                      }
                    }
                  }
                }
                """);

        BigDecimal translationCost = ModelCostCalculator.calculateTokenCost(tokenUsage, pricing, InterfaceType.OPENAI_CHAT_COMPLETIONS, responseTree);

        BigDecimal expected = new BigDecimal("249500").multiply(new BigDecimal("0.000003"))
                .add(new BigDecimal("400").multiply(new BigDecimal("0.0000006")))
                .add(new BigDecimal("100").multiply(new BigDecimal("0.000012")));
        assertEquals(expected, translationCost);
    }

    /**
     * {@code calculateTokenCost} takes a response already parsed by a caller - these tests parse the fixture
     * once, here, exactly as that caller would have.
     */
    private static JsonNode usageRoot(String response) {
        return JsonUtil.tryParse(response.getBytes());
    }
}
