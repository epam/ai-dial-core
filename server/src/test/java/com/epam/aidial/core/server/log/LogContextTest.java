package com.epam.aidial.core.server.log;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogContextTest {

    @Test
    void ofCopiesWhatLogRecordsNeed() {
        LogAttributes source = mock(LogAttributes.class);
        when(source.getTraceId()).thenReturn("trace");
        when(source.getSpanId()).thenReturn("span");
        when(source.getTraceFlags()).thenReturn("01");
        when(source.getProject()).thenReturn("project");
        when(source.getUserId()).thenReturn("user");
        when(source.getRequestMethod()).thenReturn("POST");
        when(source.getRequestUri()).thenReturn("/v1/chat");
        when(source.isResponseEnded()).thenReturn(true);
        when(source.getStatusCode()).thenReturn(502);
        when(source.getStatusMessage()).thenReturn("Bad Gateway");

        LogContext logContext = LogContext.of(source);

        assertEquals("trace", logContext.getTraceId());
        assertEquals("span", logContext.getSpanId());
        assertEquals("01", logContext.getTraceFlags());
        assertEquals("project", logContext.getProject());
        assertEquals("user", logContext.getUserId());
        assertEquals("POST", logContext.getRequestMethod());
        assertEquals("/v1/chat", logContext.getRequestUri());
        assertTrue(logContext.isResponseEnded());
        assertEquals(502, logContext.getStatusCode());
        assertEquals("Bad Gateway", logContext.getStatusMessage());
    }

    @Test
    void ofWithoutRequestLeavesRequestFieldsNull() {
        LogContext logContext = LogContext.of(mock(LogAttributes.class));

        assertNull(logContext.getRequestMethod());
        assertNull(logContext.getRequestUri());
        assertFalse(logContext.isResponseEnded());
    }

    @Test
    void ofIgnoresStatusOfUnendedResponse() {
        LogAttributes source = mock(LogAttributes.class);
        when(source.isResponseEnded()).thenReturn(false);
        when(source.getStatusCode()).thenReturn(200);
        when(source.getStatusMessage()).thenReturn("OK");

        LogContext logContext = LogContext.of(source);

        assertFalse(logContext.isResponseEnded());
        assertNull(logContext.getStatusMessage());
        assertEquals(0, logContext.getStatusCode());
    }

    @Test
    void tracingAttributesAreTheLiveMapNotCopy() {
        LogAttributes source = mock(LogAttributes.class);
        Map<String, Object> tracingAttributes = new HashMap<>();
        when(source.getTracingAttributes()).thenReturn(tracingAttributes);

        LogContext logContext = LogContext.of(source);
        tracingAttributes.put("gen_ai.usage.input_tokens", 10L);

        assertEquals(10L, logContext.getTracingAttributes().get("gen_ai.usage.input_tokens"));
    }

    @Test
    void doesNotHoldRequestOrResponseBodies() {
        boolean holdsBodies = Arrays.stream(LogContext.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == Buffer.class);

        assertFalse(holdsBodies);
    }
}
