package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ApplyDefaultDeploymentSettingsFnTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @InjectMocks
    private ApplyDefaultDeploymentSettingsFn fn;

    @Test
    public void testWithModelWithoutInterceptors() throws JsonProcessingException {
        Model model = new Model();
        Map<String, Object> defaults = Map.of("key1", true, "key2", 123, "key3", 0.45, "key4", "str");
        model.setDefaults(defaults);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);
        when(context.getDeployment()).thenReturn(model);
        JsonNode result = ProxyUtil.MAPPER.readTree("{}");

        assertTrue(fn.apply((ObjectNode) result));
        assertEquals(123, result.get("key2").asInt());
        assertEquals(0.45, result.get("key3").asDouble());
        assertEquals("str", result.get("key4").asText());
        assertTrue(result.get("key1").asBoolean());
    }

    @Test
    public void testWithModelWithInterceptors_AtFirstInterceptor() throws JsonProcessingException {
        Model model = new Model();
        Map<String, Object> defaults = Map.of("key1", true, "key2", 123, "key3", 0.45, "key4", "str");
        model.setDefaults(defaults);
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        proxyApiKeyData.setInterceptorIndex(0);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        when(context.getDeployment()).thenReturn(model);
        JsonNode result = ProxyUtil.MAPPER.readTree("{}");

        assertTrue(fn.apply((ObjectNode) result));
        assertEquals(123, result.get("key2").asInt());
        assertEquals(0.45, result.get("key3").asDouble());
        assertEquals("str", result.get("key4").asText());
        assertTrue(result.get("key1").asBoolean());
    }

    @Test
    public void testWithModelWithInterceptors_WhenInterceptorCallAnotherDeployment() throws JsonProcessingException {
        Model model = new Model();
        Map<String, Object> defaults = Map.of("key1", true, "key2", 123, "key3", 0.45, "key4", "str");
        model.setDefaults(defaults);
        model.setName("tiny-model");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setInterceptors(List.of("interceptor1"));
        apiKeyData.setInterceptorIndex(0);
        apiKeyData.setPerRequestKey("perRequestKey");
        apiKeyData.setInitialDeployment("model");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getProxyApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getInitialDeployment()).thenReturn("model");
        when(context.getDeployment()).thenReturn(model);
        JsonNode result = ProxyUtil.MAPPER.readTree("{}");

        assertTrue(fn.apply((ObjectNode) result));
        assertEquals(123, result.get("key2").asInt());
        assertEquals(0.45, result.get("key3").asDouble());
        assertEquals("str", result.get("key4").asText());
        assertTrue(result.get("key1").asBoolean());
    }

    @Test
    public void testWithModelWithInterceptors_AtSecondInterceptor() throws JsonProcessingException {
        Model model = new Model();
        Map<String, Object> defaults = Map.of("key1", true, "key2", 123, "key3", 0.45, "key4", "str");
        model.setDefaults(defaults);
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        proxyApiKeyData.setInterceptorIndex(1);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        JsonNode result = ProxyUtil.MAPPER.readTree("{}");

        assertFalse(fn.apply((ObjectNode) result));
        assertTrue(result.isEmpty());
    }

    @Test
    public void testWithModelWithInterceptors_WhenDeploymentIsCalledAfterInterceptors() throws JsonProcessingException {
        Model model = new Model();
        Map<String, Object> defaults = Map.of("key1", true, "key2", 123, "key3", 0.45, "key4", "str");
        model.setDefaults(defaults);
        model.setName("model");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setInterceptors(List.of("interceptor1"));
        apiKeyData.setInterceptorIndex(0);
        apiKeyData.setPerRequestKey("perRequestKey");
        apiKeyData.setInitialDeployment("model");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getProxyApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getInitialDeployment()).thenReturn("model");
        when(context.getDeployment()).thenReturn(model);
        JsonNode result = ProxyUtil.MAPPER.readTree("{}");

        assertFalse(fn.apply((ObjectNode) result));
        assertTrue(result.isEmpty());
    }
}
