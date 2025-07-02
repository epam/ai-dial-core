package com.epam.aidial.core.server.log.layout;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import com.epam.aidial.core.server.log.otl.OtelLogRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class OtelJsonLayout extends LayoutBase<ILoggingEvent> {

    private final ObjectMapper objectMapper;

    private String serviceName = "aidial-core";
    private String serviceVersion = "1.0.0";

    public OtelJsonLayout() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void setServiceVersion(String serviceVersion) {
        this.serviceVersion = serviceVersion;
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        try {
            OtelLogRecord logRecord = buildOtelLogRecord(event);
            return objectMapper.writeValueAsString(logRecord) + "\n";
        } catch (JsonProcessingException e) {
            addError("Failed to serialize log event", e);
            return buildFallbackJson(event) + "\n";
        }
    }

    private OtelLogRecord buildOtelLogRecord(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        String traceId = mdc.get("trace_id");
        String spanId = mdc.get("span_id");
        String traceFlags = mdc.get("trace_flags");
        String service = mdc.get("service");

        Map<String, String> mdcCopy = new HashMap<>(mdc);

        String[] fieldsToRemove = {"trace_id", "span_id", "trace_flags", "service"};
        for (String field : fieldsToRemove) {
            mdcCopy.remove(field);
        }

        Map<String, Object> details = null;
        if (!mdcCopy.isEmpty()) {
            details = new HashMap<>(mdcCopy);
        }

        Map<String, Object> resource = new HashMap<>();
        resource.put("service.name", serviceName);
        resource.put("service.version", serviceVersion);

        return OtelLogRecord.builder()
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME))
                .severityText(event.getLevel().toString())
                .severityNumber(mapSeverity(event.getLevel().toString()))
                .traceId(traceId != null ? traceId : "")
                .spanId(spanId != null ? spanId : "")
                .traceFlags(traceFlags != null ? traceFlags : "")
                .service(service != null ? service : serviceName)
                .body(OtelLogRecord.LogBody.builder()
                        .message(event.getFormattedMessage())
                        .details(details)
                        .build())
                .resource(resource)
                .build();
    }

    private String mapSeverity(String level) {
        return switch (level) {
            case "UNSPECIFIED" -> "0";
            case "TRACE" -> "1";
            case "DEBUG" -> "5";
            case "INFO" -> "9";
            case "WARN" -> "13";
            case "ERROR" -> "17";
            case "FATAL" -> "21";
            default -> "9";
        };
    }

    private String buildFallbackJson(ILoggingEvent event) {
        return String.format(
            "{\"timestamp\":\"%s\",\"severity\":\"%s\",\"message\":\"%s\",\"logger\":\"%s\"}",
            Instant.ofEpochMilli(event.getTimeStamp()),
            mapSeverity(event.getLevel().toString()),
            event.getFormattedMessage().replace("\"", "\\\""),
            event.getLoggerName()
        );
    }
}
