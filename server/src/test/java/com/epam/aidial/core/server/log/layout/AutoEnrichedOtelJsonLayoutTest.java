package com.epam.aidial.core.server.log.layout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import com.epam.aidial.core.server.ContextManager;
import com.epam.aidial.core.server.ProxyContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AutoEnrichedOtelJsonLayoutTest {

    private AutoEnrichedOtelJsonLayout layout;
    private ObjectMapper objectMapper;
    private MockedStatic<Vertx> vertxMock;
    private MockedStatic<ContextManager> contextManagerMock;
    private MockedStatic<Span> spanMock;
    private Span currentSpan;
    private SpanContext spanContext;
    private TraceFlags traceFlags;

    @BeforeEach
    void setUp() {
        layout = new AutoEnrichedOtelJsonLayout();
        layout.start();

        objectMapper = new ObjectMapper();
        
        // Mock Vertx
        vertxMock = mockStatic(Vertx.class);
        
        // Mock ContextManager
        contextManagerMock = mockStatic(ContextManager.class);
        
        // Mock Span
        spanMock = mockStatic(Span.class);
        currentSpan = mock(Span.class);
        when(currentSpan.isRecording()).thenReturn(false);
        spanMock.when(Span::current).thenReturn(currentSpan);
        spanContext = mock(SpanContext.class);
        when(currentSpan.getSpanContext()).thenReturn(spanContext);
        traceFlags = mock(TraceFlags.class);
        when(spanContext.getTraceFlags()).thenReturn(traceFlags);
    }
    
    @AfterEach
    void tearDown() {
        // Close static mocks
        if (vertxMock != null) {
            vertxMock.close();
        }
        if (contextManagerMock != null) {
            contextManagerMock.close();
        }
        if (spanMock != null) {
            spanMock.close();
        }
    }

    @Test
    void shouldFormatLogAsValidJson() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test message");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);

        String result = layout.doLayout(event);

        assertNotNull(result);
        assertTrue(result.endsWith("\n"));

        JsonNode jsonNode = objectMapper.readTree(result);
        assertNotNull(jsonNode.get("Timestamp"));
        assertNotNull(jsonNode.get("ObservedTimestamp"));
        assertEquals("INFO", jsonNode.get("SeverityText").asText());
        assertEquals(9, jsonNode.get("SeverityNumber").asInt());
        assertEquals("aidial-core", jsonNode.get("Resource").get("service.name").asText());
        assertEquals("Test message", jsonNode.get("Body").asText());
    }

    @Test
    void shouldEnrichFromProxyContextWithHttpStatus() throws Exception {
        // Setup ProxyContext
        ProxyContext proxyContext = mock(ProxyContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);

        when(request.uri()).thenReturn("/v1/test");
        when(request.method()).thenReturn(HttpMethod.POST);

        when(response.getStatusCode()).thenReturn(200);
        when(response.getStatusMessage()).thenReturn("OK");
        when(response.ended()).thenReturn(true);

        when(proxyContext.getProject()).thenReturn("test-project");
        when(proxyContext.getUserId()).thenReturn("test-user");
        when(proxyContext.getRequest()).thenReturn(request);
        when(proxyContext.getResponse()).thenReturn(response);
        
        contextManagerMock.when(ContextManager::getProxyContext).thenReturn(proxyContext);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with ProxyContext and http status");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        JsonNode attributes = jsonNode.get("Attributes");
        assertNotNull(attributes);
        assertEquals("test-project", attributes.get("user.project").asText());
        assertEquals("test-user", attributes.get("user.id").asText());
        assertEquals("/v1/test", attributes.get("request.uri").asText());
        assertEquals("POST", attributes.get("request.method").asText());
        assertEquals(200, attributes.get("response.status.code").asInt());
    }

    @Test
    void shouldEnrichFromProxyContext() throws Exception {
        // No Vertx context
        vertxMock.when(Vertx::currentContext).thenReturn(null);
        
        // Setup ProxyContext
        ProxyContext proxyContext = mock(ProxyContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(request.uri()).thenReturn("/v1/chat/completions");
        when(request.method()).thenReturn(HttpMethod.POST);
        when(response.ended()).thenReturn(false); // Response not ended yet
        
        when(proxyContext.getProject()).thenReturn("proxy-project");
        when(proxyContext.getUserId()).thenReturn("proxy-user");
        when(proxyContext.getRequest()).thenReturn(request);
        when(proxyContext.getResponse()).thenReturn(response);
        
        contextManagerMock.when(ContextManager::getProxyContext).thenReturn(proxyContext);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with ProxyContext");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        JsonNode attributes = jsonNode.get("Attributes");
        assertNotNull(attributes);
        assertEquals("proxy-project", attributes.get("user.project").asText());
        assertEquals("proxy-user", attributes.get("user.id").asText());
        assertEquals("/v1/chat/completions", attributes.get("request.uri").asText());
        assertEquals("POST", attributes.get("request.method").asText());
        // response.status.code is not set in ProxyContext, so it shouldn't be in attributes
        assertNull(attributes.get("response.status.code"));
    }

    @Test
    void shouldUseUnknownForMissingFields() throws Exception {
        // No Vertx context and no ProxyContext
        vertxMock.when(Vertx::currentContext).thenReturn(null);
        contextManagerMock.when(ContextManager::getProxyContext).thenReturn(null);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.WARN);
        event.setMessage("RouteController can't find a route to proceed the request: /v1/test");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        JsonNode attributes = jsonNode.get("Attributes");
        assertNotNull(attributes);
        assertTrue(attributes.isObject());
        assertEquals(3, attributes.size());
    }

    @Test
    void shouldEnrichFromProxyContextAndHttpStatusFromVertx() throws Exception {
        // Setup ProxyContext
        ProxyContext proxyContext = mock(ProxyContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);

        when(request.uri()).thenReturn("/v1/models");
        when(request.method()).thenReturn(HttpMethod.GET);

        when(response.getStatusCode()).thenReturn(502);
        when(response.getStatusMessage()).thenReturn("Bad Gateway");
        when(response.ended()).thenReturn(true);

        when(proxyContext.getProject()).thenReturn("proxy-project");
        when(proxyContext.getUserId()).thenReturn("proxy-user");
        when(proxyContext.getRequest()).thenReturn(request);
        when(proxyContext.getResponse()).thenReturn(response);

        contextManagerMock.when(ContextManager::getProxyContext).thenReturn(proxyContext);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with ProxyContext and http status from Vertx");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        JsonNode attributes = jsonNode.get("Attributes");
        assertNotNull(attributes);
        assertEquals("proxy-project", attributes.get("user.project").asText()); // From ProxyContext
        assertEquals("proxy-user", attributes.get("user.id").asText()); // From ProxyContext
        assertEquals("/v1/models", attributes.get("request.uri").asText()); // From ProxyContext
        assertEquals("GET", attributes.get("request.method").asText()); // From ProxyContext
        assertEquals(502, attributes.get("response.status.code").asInt()); // From Vertx context
    }

    @Test
    void shouldIncludeTraceContextFromProxyContext() throws Exception {
        // Setup ProxyContext with trace info
        ProxyContext proxyContext = mock(ProxyContext.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(response.ended()).thenReturn(false);
        when(spanContext.getTraceId()).thenReturn("22510e56eb9b21f6b03dbc038cd8fb71");
        when(spanContext.getSpanId()).thenReturn("8a46c76f1554b00a");
        when(traceFlags.asHex()).thenReturn("01");
        when(proxyContext.getResponse()).thenReturn(response);
        
        contextManagerMock.when(ContextManager::getProxyContext).thenReturn(proxyContext);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with OpenTelemetry trace");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        assertEquals("22510e56eb9b21f6b03dbc038cd8fb71", jsonNode.get("TraceId").asText());
        assertEquals("8a46c76f1554b00a", jsonNode.get("SpanId").asText());
        assertEquals("01", jsonNode.get("TraceFlags").asText());
    }

    @Test
    void shouldHandleExceptionAttributes() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.ERROR);
        event.setMessage("Error occurred");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);
        
        // Add exception
        var cause = new EncryptionException("Failed to decrypt auth setting", new Exception("BAD token"));
        Exception testException = new RuntimeException("Test exception message", cause);
        event.setThrowableProxy(new ThrowableProxy(testException));

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        JsonNode attributes = jsonNode.get("Attributes");
        assertNotNull(attributes);
        assertEquals("java.lang.RuntimeException", attributes.get("exception.type").asText());
        assertEquals("Test exception message", attributes.get("exception.message").asText());
        assertNotNull(attributes.get("exception.stacktrace"));
        assertTrue(attributes.get("exception.stacktrace").asText().contains("Caused by:"));
    }

    @Test
    void shouldMapSeverityLevels() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        // Test different log levels
        Level[] levels = {Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR};
        int[] expectedSeverities = {1, 5, 9, 13, 17};

        for (int i = 0; i < levels.length; i++) {
            LoggingEvent event = new LoggingEvent();
            event.setLoggerName(testLogger.getName());
            event.setLevel(levels[i]);
            event.setMessage("Test " + levels[i]);
            event.setTimeStamp(System.currentTimeMillis());
            event.setLoggerContext(context);

            String result = layout.doLayout(event);
            JsonNode jsonNode = objectMapper.readTree(result);

            assertEquals(levels[i].toString(), jsonNode.get("SeverityText").asText());
            assertEquals(expectedSeverities[i], jsonNode.get("SeverityNumber").asInt());
        }
    }
}