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

    @JsonProperty("Body")
    private Object body;

    @JsonProperty("Attributes")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Map<String, Object> attributes;

    @JsonProperty("TraceId")
    private String traceId;

    @JsonProperty("SpanId")
    private String spanId;

    @JsonProperty("TraceFlags")
    private String traceFlags;

    @JsonProperty("Timestamp")
    private Long timestamp;

    @JsonProperty("ObservedTimestamp")
    private Long observedTimestamp;

    @JsonProperty("SeverityText")
    private String severityText;

    @JsonProperty("SeverityNumber")
    private Integer severityNumber;

    @JsonProperty("Resource")
    private Map<String, Object> resource;

}