package com.epam.aidial.core.function.enhancement;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    public void test() throws JsonProcessingException {
        Model model = new Model();
        Map<String, Object> defaults = Map.of("key1", true, "key2", 123, "key3", 0.45, "key4", "str");
        model.setDefaults(defaults);
        when(context.getDeployment()).thenReturn(model);
        Mockito.doCallRealMethod().when(context).setRequestBody(any(Buffer.class));
        when(context.getRequestBody()).thenCallRealMethod();
        Throwable error = fn.apply((ObjectNode) ProxyUtil.MAPPER.readTree("{}"));
        assertNull(error);
        String json = context.getRequestBody().toString(StandardCharsets.UTF_8);
        ObjectNode result = (ObjectNode) ProxyUtil.MAPPER.readTree(json);
        assertNotNull(result);
        assertEquals(123, result.get("key2").asInt());
        assertEquals(0.45, result.get("key3").asDouble());
        assertEquals("str", result.get("key4").asText());
        assertTrue(result.get("key1").asBoolean());
    }
}
