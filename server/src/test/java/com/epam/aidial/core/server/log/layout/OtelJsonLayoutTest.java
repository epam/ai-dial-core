package com.epam.aidial.core.server.log.layout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtelJsonLayoutTest {

    private OtelJsonLayout layout;
    private ObjectMapper objectMapper;
    private Logger logger;

    @BeforeEach
    void setUp() {
        layout = new OtelJsonLayout();
        layout.setServiceName("test-service");
        layout.setServiceVersion("1.0.0");
        layout.start();

        objectMapper = new ObjectMapper();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger("test");
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
        assertNotNull(jsonNode.get("timestamp"));
        assertEquals("INFO", jsonNode.get("severity").asText());
        assertEquals("test-service", jsonNode.get("service").asText());
        assertEquals("Test message", jsonNode.get("body").get("message").asText());
    }

    @Test
    void shouldIncludeMdcProperties() throws Exception {
        MDC.put("request_id", "12345");
        MDC.put("user_id", "user123");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with MDC");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);
        event.setMDCPropertyMap(MDC.getCopyOfContextMap());

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        assertEquals("12345", jsonNode.get("body").get("details").get("request_id").asText());
        assertEquals("user123", jsonNode.get("body").get("details").get("user_id").asText());

        MDC.clear();
    }

    @Test
    void shouldIncludeTraceContext() throws Exception {
        // Set up trace context in MDC
        MDC.put("trace_id", "e0671734d3d00c9838ebff2396fd385d");
        MDC.put("span_id", "7219d5f67da9ef84");
        MDC.put("trace_flags", "01");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with trace context");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);
        event.setMDCPropertyMap(MDC.getCopyOfContextMap());

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        // Verify trace context is included as top-level fields
        assertEquals("e0671734d3d00c9838ebff2396fd385d", jsonNode.get("trace_id").asText());
        assertEquals("7219d5f67da9ef84", jsonNode.get("span_id").asText());
        assertEquals("01", jsonNode.get("trace_flags").asText());

        // Verify trace context is not duplicated in details
        JsonNode details = jsonNode.get("body").get("details");
        if (details != null) {
            assertNull(details.get("trace_id"));
            assertNull(details.get("span_id"));
            assertNull(details.get("trace_flags"));
        }

        MDC.clear();
    }

    @Test
    void shouldIncludeAllMdcFields() throws Exception {
        // Set up all MDC fields
        MDC.put("deployment", "test-deployment");
        MDC.put("user_hash", "user-hash-123");
        MDC.put("method", "GET");
        MDC.put("path", "/api/test");
        MDC.put("project", "test-project");
        MDC.put("status", "200");
        MDC.put("user_sub", "user-sub-123");
        MDC.put("route_name", "test-route");
        MDC.put("result", "success");
        MDC.put("service", "test-service-mdc");
        MDC.put("trace_id", "trace-id-123");
        MDC.put("span_id", "span-id-123");
        MDC.put("trace_flags", "01");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with all MDC fields");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);
        event.setMDCPropertyMap(MDC.getCopyOfContextMap());

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        // Verify system fields are included
        assertEquals("test-service-mdc", jsonNode.get("service").asText());
        assertEquals("trace-id-123", jsonNode.get("trace_id").asText());
        assertEquals("span-id-123", jsonNode.get("span_id").asText());
        assertEquals("01", jsonNode.get("trace_flags").asText());

        // Verify business fields are not included
        assertNull(jsonNode.get("deployment"));
        assertNull(jsonNode.get("user_hash"));
        assertNull(jsonNode.get("method"));
        assertNull(jsonNode.get("path"));
        assertNull(jsonNode.get("status"));
        assertNull(jsonNode.get("user_sub"));
        assertNull(jsonNode.get("route_name"));
        assertNull(jsonNode.get("result"));

        // Verify these fields are not duplicated in details
        JsonNode details = jsonNode.get("body").get("details");
        if (details != null) {
            assertNull(details.get("service"));
            assertNull(details.get("trace_id"));
            assertNull(details.get("span_id"));
            assertNull(details.get("trace_flags"));
            // Business fields should be in details since they're not fields in OtelLogRecord
            assertEquals("test-deployment", details.get("deployment").asText());
            assertEquals("user-hash-123", details.get("user_hash").asText());
            assertEquals("GET", details.get("method").asText());
            assertEquals("/api/test", details.get("path").asText());
            assertEquals("200", details.get("status").asText());
            assertEquals("user-sub-123", details.get("user_sub").asText());
            assertEquals("test-route", details.get("route_name").asText());
            assertEquals("success", details.get("result").asText());
            assertEquals("test-project", details.get("project").asText());
        }

        MDC.clear();
    }

    @Test
    void shouldHandleNullMdcFields() throws Exception {
        // Don't set any MDC fields

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger testLogger = context.getLogger("test.logger");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(testLogger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("Test with null MDC fields");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);
        // Empty MDC map
        event.setMDCPropertyMap(MDC.getCopyOfContextMap());

        String result = layout.doLayout(event);
        JsonNode jsonNode = objectMapper.readTree(result);

        // Verify system fields are present with empty strings
        assertEquals("", jsonNode.get("trace_id").asText());
        assertEquals("", jsonNode.get("span_id").asText());
        assertEquals("", jsonNode.get("trace_flags").asText());

        // Service should be "test-service" from the layout configuration
        assertEquals("test-service", jsonNode.get("service").asText());

        // Verify business fields are not included
        assertNull(jsonNode.get("deployment"));
        assertNull(jsonNode.get("user_hash"));
        assertNull(jsonNode.get("method"));
        assertNull(jsonNode.get("path"));
        assertNull(jsonNode.get("status"));
        assertNull(jsonNode.get("user_sub"));
        assertNull(jsonNode.get("route_name"));
        assertNull(jsonNode.get("result"));

        MDC.clear();
    }
}
