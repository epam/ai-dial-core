package com.epam.aidial.core.function;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CollectRequestDataFnTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @InjectMocks
    private CollectRequestDataFn fn;

    @Test
    public void test_01() throws JsonProcessingException {
        doCallRealMethod().when(context).setStreamingRequest(anyBoolean());
        when(context.isStreamingRequest()).thenCallRealMethod();

        Throwable error = fn.apply((ObjectNode) ProxyUtil.MAPPER.readTree("{\"stream\": true}"));

        assertNull(error);
        assertTrue(context.isStreamingRequest());
    }

    @Test
    public void test_02() throws JsonProcessingException {
        doCallRealMethod().when(context).setStreamingRequest(anyBoolean());
        when(context.isStreamingRequest()).thenCallRealMethod();

        Throwable error = fn.apply((ObjectNode) ProxyUtil.MAPPER.readTree("{\"stream\": false}"));

        assertNull(error);
        assertFalse(context.isStreamingRequest());
    }

    @Test
    public void test_03() throws JsonProcessingException {
        doCallRealMethod().when(context).setStreamingRequest(anyBoolean());
        when(context.isStreamingRequest()).thenCallRealMethod();

        Throwable error = fn.apply((ObjectNode) ProxyUtil.MAPPER.readTree("{}"));

        assertNull(error);
        assertFalse(context.isStreamingRequest());
    }
}
