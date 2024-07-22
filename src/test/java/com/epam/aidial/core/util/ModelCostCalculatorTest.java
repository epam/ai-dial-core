package com.epam.aidial.core.util;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.PricingUnit;
import com.epam.aidial.core.token.DeploymentCostStats;
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
        pricing.setUnit(null);
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
        pricing.setUnit(PricingUnit.TOKEN);
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setPromptTokens(10);
        deploymentCostStats.setTokenUsage(tokenUsage);
        when(context.getDeploymentCostStats()).thenReturn(deploymentCostStats);
        when(context.getTokenUsage()).thenReturn(tokenUsage);

        assertEquals(new BigDecimal("6.0"), ModelCostCalculator.calculate(context));
    }

    @Test
    public void testCalculate_CharWithoutWhitespace() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit(PricingUnit.CHAR_WITHOUT_WHITESPACE);
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
        deploymentCostStats.setResponseContentLength(10);
        deploymentCostStats.setRequestContentLength(10);
        when(context.getDeploymentCostStats()).thenReturn(deploymentCostStats);

        assertEquals(new BigDecimal("6.0"), ModelCostCalculator.calculate(context));
    }

    @Test
    public void testGetRequestContentLength_Chat() {
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
        assertEquals(10, ModelCostCalculator.getRequestContentLength(ModelType.CHAT, Buffer.buffer(request)));
    }

    @Test
    public void testGetResponseContentLength_SingleResponse() {

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
        assertEquals(24, ModelCostCalculator.getResponseContentLength(ModelType.CHAT, Buffer.buffer(response), false));
    }

    @Test
    public void testGetResponseContentLength_Streaming() {

        String response = """
                {
                    "id": "chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m",
                    "object": "chat.completion.chunk",
                    "created": 1687780896,
                    "model": "gpt-35-turbo",
                    "choices": [
                      {
                        "index": 0,
                        "finish_reason": null,
                        "delta": {
                          "content": "this"
                        }
                      }
                    ],
                    "usage": null
                  }
                """;
        assertEquals(4, ModelCostCalculator.getResponseContentLength(ModelType.CHAT, Buffer.buffer(response), true));
    }

    @Test
    public void testGetResponseContentLength_Embedding() {
        assertEquals(0, ModelCostCalculator.getResponseContentLength(ModelType.EMBEDDING, Buffer.buffer(""), false));
    }

    @Test
    public void testGetResponseContentLength_Error() {
        String response = """
                {"error": { "message": "message", "type": "type", "param": "param", "code": "code" } }
                """;
        assertEquals(0, ModelCostCalculator.getResponseContentLength(ModelType.CHAT, Buffer.buffer(response), false));
    }

    @Test
    public void testGetRequestLength_EmbeddingInputIsArray() {
        String request = """
                {
                  "input": ["text", "123"]
                }
                """;
        assertEquals(7, ModelCostCalculator.getRequestContentLength(ModelType.EMBEDDING, Buffer.buffer(request)));
    }

    @Test
    public void testGetRequestLength_EmbeddingInputIsString() {
        String request = """
                {
                  "input": "text"
                }
                """;
        assertEquals(4, ModelCostCalculator.getRequestContentLength(ModelType.EMBEDDING, Buffer.buffer(request)));
    }
}
