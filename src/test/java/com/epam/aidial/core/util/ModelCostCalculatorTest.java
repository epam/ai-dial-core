package com.epam.aidial.core.util;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.token.TokenUsage;
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

    @Test
    public void testCalculate_DeploymentIsNotModel() {
        assertNull(ModelCostCalculator.calculate(context));
    }

    @Test
    public void testCalculate_PricingIsNull() {
        when(context.getDeployment()).thenReturn(new Model());
        assertNull(ModelCostCalculator.calculate(context));
    }

    @Test
    public void testCalculate_UnknownCostUnit() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("unknown");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        assertNull(ModelCostCalculator.calculate(context));
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

        assertEquals(new BigDecimal("6.0"), ModelCostCalculator.calculate(context));
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

        assertEquals(new BigDecimal("13.0"), ModelCostCalculator.calculate(context));
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

        assertEquals(new BigDecimal("1.0"), ModelCostCalculator.calculate(context));
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

        assertEquals(new BigDecimal("6.5"), ModelCostCalculator.calculate(context));
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

        assertEquals(new BigDecimal("5.5"), ModelCostCalculator.calculate(context));
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

        assertEquals(new BigDecimal("0.7"), ModelCostCalculator.calculate(context));
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

        assertEquals(new BigDecimal("0.4"), ModelCostCalculator.calculate(context));
    }
}
