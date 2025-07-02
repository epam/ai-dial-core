package com.epam.aidial.core.server.log.otl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OtelLogRecord {

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("severity_text")
    private String severityText;

    @JsonProperty("severity_number")
    private String severityNumber;

    @JsonProperty("trace_id")
    private String traceId;

    @JsonProperty("span_id")
    private String spanId;

    @JsonProperty("trace_flags")
    private String traceFlags;

    @JsonProperty("service")
    private String service;

    @JsonProperty("body")
    private LogBody body;

    @JsonProperty("resource")
    private Map<String, Object> resource;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LogBody {
        @JsonProperty("message")
        private String message;

        @JsonProperty("details")
        private Map<String, Object> details;
    }
}
