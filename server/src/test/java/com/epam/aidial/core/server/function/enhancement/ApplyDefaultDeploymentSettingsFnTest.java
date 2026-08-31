package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ApplyDefaultDeploymentSettingsFnTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    private ApplyDefaultDeploymentSettingsFn fn;

    @BeforeEach
    public void setUp() {
        fn = new ApplyDefaultDeploymentSettingsFn(proxy, context, InterfaceType.OPENAI_CHAT_COMPLETIONS);
    }

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

    @Test
    public void testDefaultHeaders_WhenModelWithoutInterceptors() throws JsonProcessingException {
        Model model = new Model();
        model.setDefaultHeaders(Map.of("x-dial-cache-policy", "cache-priority", "x-dial-custom-header", "foo-bar"));
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);
        when(context.getDeployment()).thenReturn(model);
        MultiMap headers = stubRequestHeaders();

        fn.apply(new ChatCompletionRequest(emptyBody()));

        assertEquals("cache-priority", headers.get("x-dial-cache-policy"));
        assertEquals("foo-bar", headers.get("x-dial-custom-header"));
    }

    @Test
    public void testDefaultHeaders_WhenRequestAlreadyCarriesTheHeader() throws JsonProcessingException {
        Model model = new Model();
        model.setDefaultHeaders(Map.of("x-dial-cache-policy", "cache-priority"));
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);
        when(context.getDeployment()).thenReturn(model);
        // the client spells it in another case, which must still count as present
        MultiMap headers = stubRequestHeaders("X-DIAL-CACHE-POLICY", "availability-priority");

        fn.apply(new ChatCompletionRequest(emptyBody()));

        assertEquals(1, headers.size());
        assertEquals("availability-priority", headers.get("x-dial-cache-policy"));
    }

    @Test
    public void testDefaultHeaders_WhenInterfaceOverridesDeploymentLevel() throws JsonProcessingException {
        DeploymentInterface anthropic = new DeploymentInterface("http://anthropic");
        anthropic.setDefaultHeaders(Map.of("x-dial-custom-header", "foo-bar-2", "x-dial-custom-header-2", "some-value"));
        Model model = new Model();
        model.setDefaultHeaders(Map.of("x-dial-cache-policy", "cache-priority", "x-dial-custom-header", "foo-bar"));
        model.setInterfaces(Map.of(InterfaceType.ANTHROPIC_MESSAGES.getValue(), anthropic));
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);
        when(context.getDeployment()).thenReturn(model);
        MultiMap headers = stubRequestHeaders();

        new ApplyDefaultDeploymentSettingsFn(proxy, context, InterfaceType.ANTHROPIC_MESSAGES)
                .apply(new ChatCompletionRequest(emptyBody()));

        assertEquals("cache-priority", headers.get("x-dial-cache-policy"));
        assertEquals("foo-bar-2", headers.get("x-dial-custom-header"));
        assertEquals("some-value", headers.get("x-dial-custom-header-2"));
    }

    @Test
    public void testDefaultHeaders_AtFirstInterceptor() throws JsonProcessingException {
        Model model = new Model();
        model.setDefaultHeaders(Map.of("x-dial-cache-policy", "cache-priority", "x-dial-custom-header", "from-model"));
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        proxyApiKeyData.setInterceptorIndex(0);
        Interceptor interceptor = new Interceptor();
        interceptor.setName("interceptor1");
        interceptor.setDefaultHeaders(Map.of("x-dial-custom-header", "from-interceptor"));
        when(context.getDeployment()).thenReturn(interceptor);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        when(context.getInitialDeployment()).thenReturn("model");
        DeploymentService deploymentService = mock(DeploymentService.class);
        when(proxy.getDeploymentService()).thenReturn(deploymentService);
        when(deploymentService.findDeployment(eq(context), eq("model"))).thenReturn(model);
        MultiMap headers = stubRequestHeaders();

        fn.apply(new ChatCompletionRequest(emptyBody()));

        // the fronted deployment's headers travel down the chain from its first hop ...
        assertEquals("cache-priority", headers.get("x-dial-cache-policy"));
        // ... and the interceptor's own take precedence over them
        assertEquals("from-interceptor", headers.get("x-dial-custom-header"));
    }

    @Test
    public void testDefaultHeaders_AtSecondInterceptor() throws JsonProcessingException {
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        proxyApiKeyData.setInterceptorIndex(1);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        Interceptor interceptor = new Interceptor();
        interceptor.setName("interceptor2");
        interceptor.setDefaultHeaders(Map.of("x-dial-custom-header", "from-interceptor2"));
        when(context.getDeployment()).thenReturn(interceptor);
        MultiMap headers = stubRequestHeaders();

        fn.apply(new ChatCompletionRequest(emptyBody()));

        // only its own: the deployment's landed at the first interceptor and were forwarded from there
        assertEquals(1, headers.size());
        assertEquals("from-interceptor2", headers.get("x-dial-custom-header"));
    }

    @Test
    public void testDefaultHeaders_WhenDeploymentIsCalledAfterInterceptors() throws JsonProcessingException {
        Model model = new Model();
        model.setName("model");
        model.setDefaultHeaders(Map.of("x-dial-cache-policy", "cache-priority"));
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setInterceptors(List.of("interceptor1"));
        apiKeyData.setInterceptorIndex(0);
        apiKeyData.setPerRequestKey("perRequestKey");
        apiKeyData.setInitialDeployment("model");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getProxyApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getInitialDeployment()).thenReturn("model");
        when(context.getDeployment()).thenReturn(model);

        fn.apply(new ChatCompletionRequest(emptyBody()));

        // nothing to re-apply on the last hop, so the inbound request is not even touched
        verify(context, never()).getRequest();
    }

    private static ObjectNode emptyBody() throws JsonProcessingException {
        return (ObjectNode) ProxyUtil.MAPPER.readTree("{}");
    }

    private MultiMap stubRequestHeaders(String... headers) {
        MultiMap requestHeaders = MultiMap.caseInsensitiveMultiMap();
        for (int i = 0; i < headers.length; i += 2) {
            requestHeaders.set(headers[i], headers[i + 1]);
        }
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(requestHeaders);
        when(context.getRequest()).thenReturn(request);
        return requestHeaders;
    }
}
