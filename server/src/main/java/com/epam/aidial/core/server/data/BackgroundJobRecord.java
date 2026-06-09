package com.epam.aidial.core.server.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackgroundJobRecord {
    /** DIAL encrypted response ID — used for logging and response lookup. */
    String dialResponseId;
    /** Per-request API key — invalidated by the background job on completion or expiry. */
    String perRequestKey;
    /** OpenTelemetry trace ID — used to attribute token usage on completion. */
    String traceId;
    /** OpenTelemetry span ID — used to attribute token usage on completion. */
    String spanId;
    /** Epoch millis when the job was created — used for TTL expiry. */
    long createdAt;
    /** Whether the original request was a streaming request — used on restart to route to SSE reconnect vs polling. */
    boolean streaming;
}
