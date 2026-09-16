package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Condition;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Operator;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.PricingRate;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@SuppressWarnings("checkstyle:LineLength")
@ExtendWith(MockitoExtension.class)
public class ModelCostCalculatorTest {

    @Mock
    private ProxyContext context;

    private static PricingRate flatRate(String rate) {
        PricingRate pricingRate = new PricingRate();
        pricingRate.setRate(rate);
        return pricingRate;
    }

    @Test
    public void testCalculate_DeploymentIsNotModel() {
        assertNull(ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_PricingIsNull() {
        when(context.getDeployment()).thenReturn(new Model());
        assertNull(ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_UnknownCostUnit() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("unknown");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        assertNull(ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_TokenCost() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("token");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setPromptTokens(10);
        when(context.getTokenUsage()).thenReturn(tokenUsage);

        assertEquals(new BigDecimal("6.0"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_TokenCost_CacheRatesUnset_MatchesLegacyCost() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("token");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setPromptTokens(10);
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCachedTokens(4);
        details.setCacheWriteTokens(2);
        tokenUsage.setPromptTokensDetails(details);
        when(context.getTokenUsage()).thenReturn(tokenUsage);

        assertEquals(new BigDecimal("6.0"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_TokenCost_WithCacheReadWriteRates() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setCacheRead(flatRate("0.01"));
        pricing.setCacheWrite(flatRate("0.02"));
        pricing.setUnit("token");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setPromptTokens(10);
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCachedTokens(4);
        details.setCacheWriteTokens(2);
        tokenUsage.setPromptTokensDetails(details);
        when(context.getTokenUsage()).thenReturn(tokenUsage);

        assertEquals(new BigDecimal("5.48"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_TokenCost_ExplicitZeroCacheReadRate() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setCacheRead(flatRate("0"));
        pricing.setUnit("token");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setPromptTokens(10);
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCachedTokens(4);
        tokenUsage.setPromptTokensDetails(details);
        when(context.getTokenUsage()).thenReturn(tokenUsage);

        assertEquals(new BigDecimal("5.6"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_LengthCost_Chat_StreamIsMissing_Success() {
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("char_without_whitespace");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

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
        when(context.getResponseBody()).thenReturn(Buffer.buffer(response));

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
        when(context.getRequestBody()).thenReturn(Buffer.buffer(request));

        assertEquals(new BigDecimal("13.0"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_LengthCost_Chat_StreamIsFalse_Success() {
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("char_without_whitespace");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

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
        when(context.getResponseBody()).thenReturn(Buffer.buffer(response));

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
        when(context.getRequestBody()).thenReturn(Buffer.buffer(request));

        assertEquals(new BigDecimal("13.0"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_LengthCost_Chat_StreamIsFalse_Error() {
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("char_without_whitespace");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        String response = """
                {"error": { "message": "message", "type": "type", "param": "param", "code": "code" } }
                """;
        when(context.getResponseBody()).thenReturn(Buffer.buffer(response));

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
        when(context.getRequestBody()).thenReturn(Buffer.buffer(request));

        assertEquals(new BigDecimal("1.0"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_LengthCost_Chat_StreamIsTrue_Success() {
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("char_without_whitespace");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        String response = """
                data:   {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant"}}],"usage":null}
                 
                data:   {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"this"}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" is "}}],"usage":null}
                 
                 
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"a text"}}],"usage":null}
                 
                data: [DONE]
                 
                 
                """;
        when(context.getResponseBody()).thenReturn(Buffer.buffer(response));

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
        when(context.getRequestBody()).thenReturn(Buffer.buffer(request));

        assertEquals(new BigDecimal("6.5"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_LengthCost_Chat_StreamIsTrue_Error() {
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("char_without_whitespace");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        String response = """
                data:   {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant"}}],"usage":null}
                 
                data:   {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"this"}}],"usage":null}
                 
                data: {"error": { "message": "message", "type": "type", "param": "param", "code": "code" } }
                 
                 
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"a text"}}],"usage":null}
                 
                data: [DONE]
                 
                 
                """;
        when(context.getResponseBody()).thenReturn(Buffer.buffer(response));

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
        when(context.getRequestBody()).thenReturn(Buffer.buffer(request));

        assertEquals(new BigDecimal("5.5"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_LengthCost_EmbeddingInputIsArray() {
        Model model = new Model();
        model.setType(ModelType.EMBEDDING);
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("char_without_whitespace");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        String response = """
                {}
                """;
        when(context.getResponseBody()).thenReturn(Buffer.buffer(response));

        String request = """
                {
                  "input": ["text", "123"]
                }
                """;
        when(context.getRequestBody()).thenReturn(Buffer.buffer(request));

        assertEquals(new BigDecimal("0.7"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
    }

    @Test
    public void testCalculate_LengthCost_EmbeddingInputIsString() {
        Model model = new Model();
        model.setType(ModelType.EMBEDDING);
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("char_without_whitespace");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        String response = """
                {}
                """;
        when(context.getResponseBody()).thenReturn(Buffer.buffer(response));

        String request = """
                {
                  "input": "text"
                }
                """;
        when(context.getRequestBody()).thenReturn(Buffer.buffer(request));

        assertEquals(new BigDecimal("0.4"), ModelCostCalculator.calculate(context.getDeployment(), context.getTokenUsage(), context.getRequestBody(), context.getResponseBody(), InterfaceType.OPENAI_CHAT_COMPLETIONS, null));
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
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.000003");
        pricing.setCompletion("0.000015");
        pricing.setCacheRead(node("promptTokens", Operator.GT, 200000, flatRate("0.0000006"), flatRate("0.0000003")));
        pricing.setCacheWrite(node("promptTokens", Operator.GT, 200000,
                node("ttl", Operator.EQ, "1h", flatRate("0.000012"), flatRate("0.0000075")),
                node("ttl", Operator.EQ, "1h", flatRate("0.000006"), flatRate("0.00000375"))));
        model.setPricing(pricing);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(250000);
        tokenUsage.setCompletionTokens(0);

        String response = """
                {
                  "usage": {
                    "input_tokens": 249500,
                    "cache_read_input_tokens": 400,
                    "cache_creation_input_tokens": 100,
                    "cache_creation": { "ephemeral_5m_input_tokens": 0, "ephemeral_1h_input_tokens": 100 },
                    "service_tier": "standard"
                  }
                }
                """;

        BigDecimal cost = ModelCostCalculator.calculate(model, tokenUsage, null, Buffer.buffer(response),
                InterfaceType.ANTHROPIC_MESSAGES, null);

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
        Model model = new Model();
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
        model.setPricing(pricing);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(150000);
        tokenUsage.setCompletionTokens(200);

        String response = """
                {
                  "usage": {
                    "prompt_tokens": 150000,
                    "prompt_tokens_details": { "cached_tokens": 800, "cache_write_tokens": 50 },
                    "completion_tokens": 200
                  },
                  "service_tier": "flex"
                }
                """;

        BigDecimal cost = ModelCostCalculator.calculate(model, tokenUsage, null, Buffer.buffer(response),
                InterfaceType.OPENAI_CHAT_COMPLETIONS, null);

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
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.0000025");
        pricing.setCompletion("0.00001");
        pricing.setCacheWrite(node("promptTokens", Operator.GT, 272000,
                node("serviceTier", Operator.EQ, "flex", flatRate("0.00000625"), flatRate("0.0000125")),
                node("serviceTier", Operator.EQ, "flex", flatRate("0.000003125"),
                        node("serviceTier", Operator.EQ, "priority", flatRate("0.0000125"), flatRate("0.00000625")))));
        model.setPricing(pricing);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(150000);
        tokenUsage.setCompletionTokens(200);
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCacheWriteTokens(50);
        tokenUsage.setPromptTokensDetails(details);

        String response = """
                {
                  "usage": {
                    "prompt_tokens": 150000,
                    "prompt_tokens_details": { "cached_tokens": 800, "cache_write_tokens": 50 },
                    "completion_tokens": 200
                  }
                }
                """;

        BigDecimal cost = ModelCostCalculator.calculate(model, tokenUsage, null, Buffer.buffer(response),
                InterfaceType.OPENAI_CHAT_COMPLETIONS, null);

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
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setCacheRead(node("serviceTier", Operator.EQ, "flex", flatRate("0.01"), flatRate("0.02")));
        model.setPricing(pricing);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(10);
        tokenUsage.setCompletionTokens(10);
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCachedTokens(4);
        tokenUsage.setPromptTokensDetails(details);

        BigDecimal cost = ModelCostCalculator.calculate(model, tokenUsage, null, null,
                InterfaceType.OPENAI_CHAT_COMPLETIONS, null);

        // no responseBody at all -> counter falls back to PromptTokensDetails.cachedTokens=4,
        // rate resolution never matches serviceTier -> falls back to ifFalse=0.02
        BigDecimal expected = new BigDecimal("6").multiply(new BigDecimal("0.1"))
                .add(new BigDecimal("10").multiply(new BigDecimal("0.5")))
                .add(new BigDecimal("4").multiply(new BigDecimal("0.02")));
        assertEquals(expected, cost);
    }

    /** Design doc §4: translation-mode parity - same price as the passthrough §3 case for a byte-identical envelope. */
    @Test
    public void testCalculate_DecisionTree_TranslationModeParityWithPassthrough() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.000003");
        pricing.setCompletion("0.000015");
        pricing.setCacheRead(node("promptTokens", Operator.GT, 200000, flatRate("0.0000006"), flatRate("0.0000003")));
        pricing.setCacheWrite(node("promptTokens", Operator.GT, 200000,
                node("ttl", Operator.EQ, "1h", flatRate("0.000012"), flatRate("0.0000075")),
                node("ttl", Operator.EQ, "1h", flatRate("0.000006"), flatRate("0.00000375"))));
        model.setPricing(pricing);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(250000);
        tokenUsage.setCompletionTokens(0);

        String translatedResponse = """
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
                """;

        BigDecimal translationCost = ModelCostCalculator.calculate(model, tokenUsage, null, Buffer.buffer(translatedResponse),
                InterfaceType.OPENAI_CHAT_COMPLETIONS, null);

        BigDecimal expected = new BigDecimal("249500").multiply(new BigDecimal("0.000003"))
                .add(new BigDecimal("400").multiply(new BigDecimal("0.0000006")))
                .add(new BigDecimal("100").multiply(new BigDecimal("0.000012")));
        assertEquals(expected, translationCost);
    }
}
