package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.ContextManager;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.RouteType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.opentelemetry.api.common.AttributeKey.booleanKey;
import static io.opentelemetry.api.common.AttributeKey.doubleKey;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringArrayKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class RequestLogRecordProcessorTest {

    private final List<LogRecordData> exported = new ArrayList<>();
    private MockedStatic<ContextManager> contextManagerMock;
    private SdkLoggerProvider loggerProvider;

    @BeforeEach
    void setUp() {
        contextManagerMock = mockStatic(ContextManager.class);
        // added after the exporting stand-in, first in line as AiDial adds it: the exporter copies the record on emit
        loggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor((context, logRecord) -> exported.add(logRecord.toLogRecordData()))
                .addLogRecordProcessorFirst(new RequestLogRecordProcessor())
                .build();
    }

    @AfterEach
    void tearDown() {
        contextManagerMock.close();
        loggerProvider.close();
    }

    @Test
    void writesRequestAndSpanAttributesTyped() {
        ProxyContext proxyContext = proxyContext(true);
        when(proxyContext.getProject()).thenReturn("project-1");
        when(proxyContext.getUserId()).thenReturn("user-1");
        Route route = new Route();
        route.setName("vector-store");
        when(proxyContext.getRoute()).thenReturn(route);
        when(proxyContext.getRouteType()).thenReturn(RouteType.GLOBAL);
        Map<String, Object> tracingAttributes = new LinkedHashMap<>();
        tracingAttributes.put("gen_ai.conversation.id", "session-1");
        tracingAttributes.put("gen_ai.usage.input_tokens", 10L);
        tracingAttributes.put("gen_ai.request.stream", true);
        tracingAttributes.put("gen_ai.request.temperature", 0.5);
        tracingAttributes.put("gen_ai.request.encoding_formats", List.of("base64"));
        tracingAttributes.put("odd", new Object() {
            @Override
            public String toString() {
                return "as text";
            }
        });
        when(proxyContext.getTracingAttributes()).thenReturn(tracingAttributes);
        contextManagerMock.when(ContextManager::getProxyContext).thenReturn(proxyContext);

        Attributes attributes = emit();

        assertEquals("project-1", attributes.get(stringKey("user.project")));
        assertEquals("user-1", attributes.get(stringKey("user.id")));
        assertEquals("POST", attributes.get(stringKey("request.method")));
        assertEquals("/v1/chat/completions", attributes.get(stringKey("request.uri")));
        assertEquals("OK", attributes.get(stringKey("response.status")));
        assertEquals(200L, attributes.get(longKey("response.status.code")));
        assertEquals("vector-store", attributes.get(stringKey("dial.route.name")));
        assertEquals("global", attributes.get(stringKey("dial.route.type")));
        assertEquals("session-1", attributes.get(stringKey("gen_ai.conversation.id")));
        assertEquals(10L, attributes.get(longKey("gen_ai.usage.input_tokens")));
        assertEquals(true, attributes.get(booleanKey("gen_ai.request.stream")));
        assertEquals(0.5, attributes.get(doubleKey("gen_ai.request.temperature")));
        assertEquals(List.of("base64"), attributes.get(stringArrayKey("gen_ai.request.encoding_formats")));
        assertEquals("as text", attributes.get(stringKey("odd")));
    }

    @Test
    void leavesOutNullValuesAndUnendedResponse() {
        ProxyContext proxyContext = proxyContext(false);
        when(proxyContext.getTracingAttributes()).thenReturn(new LinkedHashMap<>());
        contextManagerMock.when(ContextManager::getProxyContext).thenReturn(proxyContext);

        Attributes attributes = emit();

        assertNull(attributes.get(stringKey("user.project")));
        assertNull(attributes.get(stringKey("user.id")));
        assertNull(attributes.get(longKey("response.status.code")));
        assertEquals(2, attributes.size(), attributes.toString());
    }

    @Test
    void writesNothingOutsideRequest() {
        contextManagerMock.when(ContextManager::getProxyContext).thenReturn(null);

        assertTrue(emit().isEmpty());
    }

    private Attributes emit() {
        loggerProvider.get("test").logRecordBuilder().setBody("line").emit();
        assertEquals(1, exported.size());
        return exported.getFirst().getAttributes();
    }

    private static ProxyContext proxyContext(boolean responseEnded) {
        ProxyContext proxyContext = mock(ProxyContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.uri()).thenReturn("/v1/chat/completions");
        when(response.ended()).thenReturn(responseEnded);
        when(response.getStatusMessage()).thenReturn("OK");
        when(response.getStatusCode()).thenReturn(200);
        when(proxyContext.getRequest()).thenReturn(request);
        when(proxyContext.getResponse()).thenReturn(response);
        return proxyContext;
    }
}
