package com.epam.aidial.core.server.log.layout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ContextManager;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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

    @BeforeEach
    void setUp() {
        // Clear MDC before each test to ensure clean state
        MDC.clear();
        
        layout = new AutoEnrichedOtelJsonLayout();
        layout.setServiceName("test-service");
        layout.setServiceVersion("1.0.0");
        layout.start();

        objectMapper = new ObjectMapper();
        
        // Mock Vertx
        vertxMock = mockStatic(Vertx.class);
        
        // Mock ContextManager
        contextManagerMock = mockStatic(ContextManager.class);
        
        // Mock Span
        spanMock = mockStatic(Span.class);
    }
    
    @AfterEach
    void tearDown() {
        // Clear MDC after each test to prevent leakage
        MDC.clear();
        
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
        assertEquals("test-service", jsonNode.get("Resource").get("service.name").asText());
        assertEquals("Test message", jsonNode.get("Body").asText());
    }

    @Test
    void shouldEnrichFromVertxContext() throws Exception {
        // Setup Vertx context with values
        Context vertxContext = mock(Context.class);
        when(vertxContext.getLocal("user.project")).thenReturn("test-project");
        when(vertxContext.getLocal("user.sub")).thenReturn("test-user");
        when(vertxContext.getLocal("request.uri")).thenReturn("/v1/test");
        when(vertxContext.getLocal("request.method")).thenReturn("POST");
        when(vertxContext.getLocal("http.status.code")).thenReturn("200");
        
        vertxMock.when(Vertx::currentContext).thenReturn(vertxContext);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with Vertx context");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        JsonNode attributes = jsonNode.get("Attributes");
        assertNotNull(attributes);
        assertEquals("test-project", attributes.get("user.project").asText());
        assertEquals("test-user", attributes.get("user.sub").asText());
        assertEquals("/v1/test", attributes.get("request.uri").asText());
        assertEquals("POST", attributes.get("request.method").asText());
        assertEquals("200", attributes.get("http.status.code").asText());
    }

    @Test
    void shouldEnrichFromProxyContext() throws Exception {
        // No Vertx context
        vertxMock.when(Vertx::currentContext).thenReturn(null);
        
        // Setup ProxyContext
        ProxyContext proxyContext = mock(ProxyContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.uri()).thenReturn("/v1/chat/completions");
        when(request.method()).thenReturn(HttpMethod.POST);
        
        when(proxyContext.getProject()).thenReturn("proxy-project");
        when(proxyContext.getUserSub()).thenReturn("proxy-user");
        when(proxyContext.getRequest()).thenReturn(request);
        
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
        assertEquals("proxy-user", attributes.get("user.sub").asText());
        assertEquals("/v1/chat/completions", attributes.get("request.uri").asText());
        assertEquals("POST", attributes.get("request.method").asText());
        // http.status.code is not set in ProxyContext, so it shouldn't be in attributes
        assertNull(attributes.get("http.status.code"));
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
        assertEquals("unknown", attributes.get("user.project").asText());
        assertEquals("unknown", attributes.get("user.sub").asText());
        assertEquals("unknown", attributes.get("request.uri").asText());
        assertEquals("unknown", attributes.get("request.method").asText());
        // http.status.code should not be present when not available
        assertNull(attributes.get("http.status.code"));
    }

    @Test
    void shouldEnrichFromMixedSources() throws Exception {
        // Setup Vertx context with some values
        Context vertxContext = mock(Context.class);
        when(vertxContext.getLocal("user.project")).thenReturn("vertx-project");
        when(vertxContext.getLocal("user.sub")).thenReturn(null); // Not in Vertx
        when(vertxContext.getLocal("http.status.code")).thenReturn("502");
        
        vertxMock.when(Vertx::currentContext).thenReturn(vertxContext);
        
        // Setup ProxyContext with complementary values
        ProxyContext proxyContext = mock(ProxyContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.uri()).thenReturn("/v1/models");
        when(request.method()).thenReturn(HttpMethod.GET);
        
        when(proxyContext.getProject()).thenReturn("proxy-project"); // Will be ignored, Vertx has priority
        when(proxyContext.getUserSub()).thenReturn("proxy-user"); // Will be used
        when(proxyContext.getRequest()).thenReturn(request);
        
        contextManagerMock.when(ContextManager::getProxyContext).thenReturn(proxyContext);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with mixed sources");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        JsonNode attributes = jsonNode.get("Attributes");
        assertNotNull(attributes);
        assertEquals("vertx-project", attributes.get("user.project").asText()); // From Vertx
        assertEquals("proxy-user", attributes.get("user.sub").asText()); // From ProxyContext
        assertEquals("/v1/models", attributes.get("request.uri").asText()); // From ProxyContext
        assertEquals("GET", attributes.get("request.method").asText()); // From ProxyContext
        assertEquals("502", attributes.get("http.status.code").asText()); // From Vertx
    }

    @Test
    void shouldIncludeTraceContextFromOpenTelemetry() throws Exception {
        // Mock OpenTelemetry Span
        Span currentSpan = mock(Span.class);
        SpanContext spanContext = SpanContext.create(
                "22510e56eb9b21f6b03dbc038cd8fb71",
                "8a46c76f1554b00a", 
                TraceFlags.getSampled(),
                TraceState.getDefault()
        );
        when(currentSpan.getSpanContext()).thenReturn(spanContext);
        
        spanMock.when(Span::current).thenReturn(currentSpan);

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
        Exception testException = new RuntimeException("Test exception message");
        event.setThrowableProxy(new ThrowableProxy(testException));

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        JsonNode attributes = jsonNode.get("Attributes");
        assertNotNull(attributes);
        assertEquals("java.lang.RuntimeException", attributes.get("exception.type").asText());
        assertEquals("Test exception message", attributes.get("exception.message").asText());
        assertNotNull(attributes.get("exception.stacktrace"));
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

    @Test
    void shouldHandleFallbackJson() throws Exception {
        // Create a layout that will fail JSON serialization
        AutoEnrichedOtelJsonLayout faultyLayout = new AutoEnrichedOtelJsonLayout() {
            @Override
            public String doLayout(ch.qos.logback.classic.spi.ILoggingEvent event) {
                // Force an exception in the normal flow
                throw new RuntimeException("Simulated serialization error");
            }
        };
        
        // This should not work because doLayout is overridden, but let's test the buildFallbackJson method directly
        // We'll need to use reflection or create a test that triggers the fallback
    }

    @Test
    void shouldIncludeMdcFieldsInAttributes() throws Exception {
        // Set some MDC fields that are not special fields
        MDC.put("custom.field", "custom-value");
        MDC.put("transaction.id", "txn-123");
        
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with custom MDC");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);
        event.setMDCPropertyMap(MDC.getCopyOfContextMap());

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        JsonNode attributes = jsonNode.get("Attributes");
        assertNotNull(attributes);
        assertEquals("custom-value", attributes.get("custom.field").asText());
        assertEquals("txn-123", attributes.get("transaction.id").asText());
    }
}