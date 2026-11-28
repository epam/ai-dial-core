package com.epam.aidial.core.server.log;

import com.epam.aidial.core.server.ContextManager;
import com.epam.aidial.core.server.ProxyContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.opentelemetry.api.common.AttributeKey.booleanKey;
import static io.opentelemetry.api.common.AttributeKey.doubleKey;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringArrayKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Gives each OTLP log record of a request the request and span attributes its console JSON line carries, typed as
 * on the span. The logback appender emits on the logging thread, so the request's context is still current here.
 * Must run before the exporting processor, which copies the record when it is emitted.
 */
public class RequestLogRecordProcessor implements LogRecordProcessor {

    @Override
    public void onEmit(Context context, ReadWriteLogRecord logRecord) {
        ProxyContext proxyContext = ContextManager.getProxyContext();
        if (proxyContext == null) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        RequestLogAttributes.putRequestAttributes(proxyContext, attributes);
        attributes.putAll(proxyContext.getTracingAttributes());
        attributes.forEach((name, value) -> setAttribute(logRecord, name, value));
    }

    private static void setAttribute(ReadWriteLogRecord logRecord, String name, Object value) {
        switch (value) {
            case null -> {
                // OTLP has no null attribute
            }
            case String text -> logRecord.setAttribute(stringKey(name), text);
            case Boolean flag -> logRecord.setAttribute(booleanKey(name), flag);
            case Long number -> logRecord.setAttribute(longKey(name), number);
            case Integer number -> logRecord.setAttribute(longKey(name), number.longValue());
            case Double number -> logRecord.setAttribute(doubleKey(name), number);
            case List<?> values -> logRecord.setAttribute(stringArrayKey(name), values.stream().map(String::valueOf).toList());
            default -> logRecord.setAttribute(stringKey(name), String.valueOf(value));
        }
    }
}
