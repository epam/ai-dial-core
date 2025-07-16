package com.epam.aidial.core.server.log.layout;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.slf4j.MDC;

public class AutoEnrichedOtelJsonLayout extends OtelJsonLayout {

    @Override
    public String doLayout(ILoggingEvent event) {
        enrichMDCFromContext();

        if (event.getThrowableProxy() != null) {
            enrichExceptionInfo(event);
            autoEnrichOpenTelemetry(event);
        }

        return super.doLayout(event);
    }

    private void enrichMDCFromContext() {
        addContextFieldToMDC("user.project");
        addContextFieldToMDC("user.sub");
        addContextFieldToMDC("request.uri");
        addContextFieldToMDC("request.method");

    }

    private void enrichExceptionInfo(ILoggingEvent event) {
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            MDC.put("exception.type", throwableProxy.getClassName());
            MDC.put("exception.message", throwableProxy.getMessage() != null ? throwableProxy.getMessage() : "");
            MDC.put("exception.stack_trace", buildStackTrace(throwableProxy));
        }
    }

    private String buildStackTrace(IThrowableProxy throwableProxy) {
        StringBuilder sb = new StringBuilder();
        sb.append(throwableProxy.getClassName()).append(": ").append(throwableProxy.getMessage()).append("\n");

        StackTraceElementProxy[] stackTrace = throwableProxy.getStackTraceElementProxyArray();
        if (stackTrace != null) {
            for (StackTraceElementProxy element : stackTrace) {
                sb.append("\tat ").append(element.getStackTraceElement().toString()).append("\n");
            }
        }

        IThrowableProxy cause = throwableProxy.getCause();
        if (cause != null) {
            sb.append("Caused by: ").append(buildStackTrace(cause));
        }

        return sb.toString();
    }

    private void addContextFieldToMDC(String key) {
        if (MDC.get(key) == null) {
            String value = getContextValue(key);
            if (value != null) {
                MDC.put(key, value);
            }
        }
    }

    private String getContextValue(String key) {
        String value = MDC.get(key);
        if (value != null) {
            return value;
        }

        try {
            Context vertxContext = Vertx.currentContext();
            if (vertxContext != null) {
                return vertxContext.getLocal(key);
            }
        } catch (Exception e) {
            // Ignore
        }

        return null;
    }

    private void autoEnrichOpenTelemetry(ILoggingEvent event) {
        try {
            Span currentSpan = Span.current();
            if (currentSpan.isRecording() && event.getThrowableProxy() != null) {
                String userProject = getContextValue("user.project");
                String userSub = getContextValue("user.sub");
                String requestUri = getContextValue("request.uri");
                String requestMethod = getContextValue("request.method");

                Throwable throwable = recreateThrowable(event.getThrowableProxy());

                currentSpan.recordException(throwable, Attributes.of(
                        AttributeKey.stringKey("exception.type"), throwable.getClass().getSimpleName(),
                        AttributeKey.stringKey("exception.message"), throwable.getMessage() != null ? throwable.getMessage() : "",
                        AttributeKey.stringKey("user.project"), userProject != null ? userProject : "unknown",
                        AttributeKey.stringKey("user.sub"), userSub != null ? userSub : "unknown",
                        AttributeKey.stringKey("request.uri"), requestUri != null ? requestUri : "unknown",
                        AttributeKey.stringKey("request.method"), requestMethod != null ? requestMethod : "unknown"
                ));
                currentSpan.setStatus(StatusCode.ERROR, throwable.getMessage());
            }
        } catch (Exception e) {
            // Ignore enrich exceptions
        }
    }

    private Throwable recreateThrowable(IThrowableProxy throwableProxy) {
        if (throwableProxy == null) {
            return new RuntimeException("Unknown exception");
        }

        String className = throwableProxy.getClassName();
        String message = throwableProxy.getMessage();

        try {
            Class<?> exceptionClass = Class.forName(className);
            if (Throwable.class.isAssignableFrom(exceptionClass)) {
                try {
                    return (Throwable) exceptionClass.getConstructor(String.class).newInstance(message);
                } catch (Exception e) {
                    try {
                        return (Throwable) exceptionClass.getConstructor().newInstance();
                    } catch (Exception e2) {
                        return new RuntimeException(className + ": " + message);
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            // if class not found, then create RuntimeException
        }

        return new RuntimeException(className + ": " + message);
    }
}
