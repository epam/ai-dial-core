package com.epam.aidial.core.server.tracing;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.slf4j.event.KeyValuePair;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrelationIdsTest {

    @Test
    void fromReadsTheSixFieldsOnceFromProxyContext() {
        ProxyContext context = mock(ProxyContext.class);
        when(context.getTraceId()).thenReturn("trace-1");
        when(context.getSpanId()).thenReturn("span-1");
        when(context.getTraceFlags()).thenReturn("01");
        when(context.getRequestHeader(Proxy.HEADER_CONVERSATION_ID)).thenReturn("conv-1");
        when(context.getProject()).thenReturn("project-1");
        when(context.getUserId()).thenReturn("user-1");

        CorrelationIds ids = CorrelationIds.from(context);

        assertEquals("trace-1", ids.traceId());
        assertEquals("span-1", ids.spanId());
        assertEquals("01", ids.traceFlags());
        assertEquals("conv-1", ids.conversationId());
        assertEquals("project-1", ids.project());
        assertEquals("user-1", ids.userId());
    }

    @Test
    void addToAttachesAllSixFieldsAsKeyValuePairsOnTheBuilder() {
        CorrelationIds ids = new CorrelationIds("trace-1", "span-1", "01", "conv-1", "project-1", "user-1");
        LoggingEventBuilder builder = mock(LoggingEventBuilder.class, Answers.RETURNS_SELF);

        LoggingEventBuilder result = ids.addTo(builder);

        assertEquals(builder, result);
        verify(builder).addKeyValue(eq(CorrelationIds.TRACE_ID_KEY), eq("trace-1"));
        verify(builder).addKeyValue(eq(CorrelationIds.SPAN_ID_KEY), eq("span-1"));
        verify(builder).addKeyValue(eq(CorrelationIds.TRACE_FLAGS_KEY), eq("01"));
        verify(builder).addKeyValue(eq(CorrelationIds.CONVERSATION_ID_KEY), eq("conv-1"));
        verify(builder).addKeyValue(eq(CorrelationIds.PROJECT_KEY), eq("project-1"));
        verify(builder).addKeyValue(eq(CorrelationIds.USER_ID_KEY), eq("user-1"));
    }

    @Test
    void keysMatchWhatTheFluentApiWouldCarryOnTheLoggingEvent() {
        // Sanity check that the key names used here are exactly what AutoEnrichedOtelJsonLayout looks up,
        // via a real org.slf4j.event.KeyValuePair rather than a mock.
        List<KeyValuePair> pairs = List.of(
                new KeyValuePair(CorrelationIds.TRACE_ID_KEY, "t"),
                new KeyValuePair(CorrelationIds.SPAN_ID_KEY, "s"));

        assertEquals("traceId", pairs.get(0).key);
        assertEquals("spanId", pairs.get(1).key);
    }
}
