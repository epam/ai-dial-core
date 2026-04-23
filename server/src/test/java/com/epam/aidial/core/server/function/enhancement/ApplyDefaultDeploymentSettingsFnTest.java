package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        ObjectNode result = (ObjectNode) ProxyUtil.MAPPER.readTree("{}");

        assertTrue(fn.apply(new ChatCompletionRequest(result)));
        assertEquals(123, result.get("key2").asInt());
        assertEquals(0.45, result.get("key3").asDouble());
        assertEquals("str", result.get("key4").asText());
        assertTrue(result.get("key1").asBoolean());
    }

    @Test
    public void testWithModelWithoutInterceptors_WhenComplexDefaults() throws JsonProcessingException {
        Model model = new Model();
        Map<String, Object> defaults = Map.of("a", Map.of("b", 1, "c", "test"));
        model.setDefaults(defaults);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);
        when(context.getDeployment()).thenReturn(model);
        ObjectNode result = (ObjectNode) ProxyUtil.MAPPER.readTree("""
                {
                 "a": {"b" : 2, "d": "foo"}
                }
                """);

        assertTrue(fn.apply(new ChatCompletionRequest(result)));
        assertEquals(2, result.get("a").get("b").asInt());
        assertEquals("foo", result.get("a").get("d").asText());
        assertEquals("test", result.get("a").get("c").asText());
    }

    @Test
    public void testWithModelWithInterceptors_AtFirstInterceptor() throws JsonProcessingException {
        Model model = new Model();
        Map<String, Object> defaults = Map.of("key1", true, "key2", 123, "key3", 0.45, "key4", "str");
        model.setDefaults(defaults);
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        proxyApiKeyData.setInterceptorIndex(0);
        Interceptor interceptor = new Interceptor();
        interceptor.setName("interceptor1");
        interceptor.setDefaults(Map.of("custom_fields", Map.of("interceptor_configuration", Map.of("foo", "bar"))));
        when(context.getDeployment()).thenReturn(interceptor);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        when(context.getDeployment()).thenReturn(interceptor);

        when(context.getInitialDeployment()).thenReturn("model");
        DeploymentService deploymentService = mock(DeploymentService.class);
        when(proxy.getDeploymentService()).thenReturn(deploymentService);
        when(deploymentService.findDeployment(eq(context), eq("model"))).thenReturn(model);

        ObjectNode result = (ObjectNode) ProxyUtil.MAPPER.readTree("{}");

        assertTrue(fn.apply(new ChatCompletionRequest(result)));
        assertEquals(123, result.get("key2").asInt());
        assertEquals(0.45, result.get("key3").asDouble());
        assertEquals("str", result.get("key4").asText());
        assertTrue(result.get("key1").asBoolean());
        ObjectNode interceptorConfig = (ObjectNode) result.get("custom_fields").get("interceptor_configuration");
        assertEquals("bar", interceptorConfig.get("foo").asText());
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
        ObjectNode result = (ObjectNode) ProxyUtil.MAPPER.readTree("{}");

        assertTrue(fn.apply(new ChatCompletionRequest(result)));
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
        Interceptor interceptor = new Interceptor();
        interceptor.setName("interceptor2");
        interceptor.setDefaults(Map.of("custom_fields", Map.of("interceptor_configuration", Map.of("foo", "bar"))));
        when(context.getDeployment()).thenReturn(interceptor);
        ObjectNode result = (ObjectNode) ProxyUtil.MAPPER.readTree("""
                {
                 "custom_fields": {
                   "interceptor_configuration": {
                     "x": "val"
                   }
                 }
                }
                """);

        assertTrue(fn.apply(new ChatCompletionRequest(result)));
        assertEquals("""
                {"custom_fields":{"interceptor_configuration":{"foo":"bar"}}}""", result.toString());
    }

    @Test
    public void testWithModelWithInterceptors_WhenDeploymentIsCalledAfterInterceptors() throws JsonProcessingException {
        Model model = new Model();
        Map<String, Object> defaults = Map.of("key1", true, "key2", 123, "key3", 0.45, "key4", "str");
        model.setDefaults(defaults);
        model.setName("model");
        Interceptor interceptor = new Interceptor();
        interceptor.setDefaults(Map.of("custom_fields", Map.of("interceptor_configuration", Map.of("foo", "bar"))));
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setInterceptors(List.of("interceptor1"));
        apiKeyData.setInterceptorIndex(0);
        apiKeyData.setPerRequestKey("perRequestKey");
        apiKeyData.setInitialDeployment("model");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getProxyApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getInitialDeployment()).thenReturn("model");
        when(context.getDeployment()).thenReturn(model);
        ObjectNode result = (ObjectNode) ProxyUtil.MAPPER.readTree("""
                {
                 "custom_fields": {
                   "interceptor_configuration": {
                     "x": "val"
                   }
                 }
                }
                """);

        assertTrue(fn.apply(new ChatCompletionRequest(result)));
        assertEquals("{}", result.toString());
    }
}
