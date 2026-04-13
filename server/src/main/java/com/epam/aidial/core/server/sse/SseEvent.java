package com.epam.aidial.core.server.sse;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class SseEvent {
    private final String event;  // type, defaults to "message"
    private final String data;   // payload
    private final String id;     // last event id
    private final Integer retry; // reconnection delay, optional

    public SseEvent copyWith(String data) {
        return new SseEvent(this.event, data, this.id, this.retry);
    }

    /**
     * Serialize this SSEEvent into text/event-stream format.
     * Lines are separated by '\n'. The event is terminated by a blank line.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        // id field
        if (id != null && id.indexOf('\0') == -1) {
            sb.append("id: ").append(id).append('\n');
        }

        // event type (omit if "message" to follow default semantics)
        if (event != null && !event.isEmpty() && !"message".equals(event)) {
            sb.append("event: ").append(event).append('\n');
        }

        // retry field
        if (retry != null) {
            sb.append("retry: ").append(retry).append('\n');
        }

        // data field(s): split by newline and send one data line per segment
        if (data != null) {
            int start = 0;
            int len = data.length();

            for (int i = 0; i < len; i++) {
                char c = data.charAt(i);
                if (c == '\n') {
                    String line = data.substring(start, i);
                    sb.append("data: ").append(line).append('\n');
                    start = i + 1;
                }
            }
            // remaining part after the last '\n' (or entire string if no '\n')
            if (start <= len) {
                String line = data.substring(start);
                sb.append("data: ").append(line).append('\n');
            }
        }

        // end of event: blank line
        sb.append('\n');

        return sb.toString();
    }
}
