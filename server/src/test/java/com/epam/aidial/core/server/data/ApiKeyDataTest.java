package com.epam.aidial.core.server.data;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyDataTest {

    @Test
    void collectHeadersCaseInsensitive() {
        ApiKeyData apiKeyData = new ApiKeyData();
        MultiMap header = MultiMap.caseInsensitiveMultiMap()
                .add(Proxy.HEADER_CONVERSATION_ID.toLowerCase(), Proxy.HEADER_CONVERSATION_ID)
                .add(Proxy.HEADER_JOB_TITLE.toUpperCase(), Proxy.HEADER_JOB_TITLE);
        ProxyContext context = mock(ProxyContext.class, Mockito.RETURNS_DEEP_STUBS);
        when(context.getApiKeyData().getHttpHeaders()).thenReturn(Map.of());
        when(context.getRequest().headers()).thenReturn(header);

        ApiKeyData.initFromContext(apiKeyData, context);

        Map<String, String> httpHeaders = apiKeyData.getHttpHeaders();
        assertEquals(2, httpHeaders.size());
    }
}
