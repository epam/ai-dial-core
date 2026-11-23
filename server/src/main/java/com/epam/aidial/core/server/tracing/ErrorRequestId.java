package com.epam.aidial.core.server.tracing;

import com.epam.aidial.core.server.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.opentelemetry.api.trace.TraceId;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Puts the request's trace id into an error answer, so that a user can give it to an admin: at the end of the
 * error message, and in {@code X-Request-ID} when the answer has none.
 */
@UtilityClass
public class ErrorRequestId {

    public static final String HEADER_REQUEST_ID = "X-Request-ID";
    // keeps the parse and the write on the event loop short
    private static final int MAX_BODY_SIZE = 64 * 1024;

    public boolean isEnabled(TracingSettings settings, String traceId) {
        return settings != null && settings.errorRequestId() && TraceId.isValid(traceId);
    }

    /**
     * Sets {@code X-Request-ID} on an error answer and returns the body to send, which is {@code body} itself when it
     * is left unchanged. A relay calls it after the origin's headers are copied, so that it sees them.
     */
    public Buffer append(TracingSettings settings, String traceId, int status, HttpServerResponse response, Buffer body) {
        if (status < 400 || !isEnabled(settings, traceId)) {
            return body;
        }
        MultiMap headers = response.headers();
        String mediaType = mediaType(headers.get(HttpHeaders.CONTENT_TYPE));
        if ("text/event-stream".equals(mediaType)) {
            return body;
        }
        if (!headers.contains(HEADER_REQUEST_ID)) {
            headers.set(HEADER_REQUEST_ID, traceId);
        }
        String encoding = headers.get(HttpHeaders.CONTENT_ENCODING);
        if (body.length() == 0 || body.length() > MAX_BODY_SIZE
                || (encoding != null && !"identity".equalsIgnoreCase(encoding.trim()))) {
            return body;
        }

        boolean json;
        if (mediaType == null) {
            json = body.getByte(0) == '{';
        } else if (mediaType.equals("application/json") || mediaType.endsWith("+json")) {
            json = true;
        } else if (mediaType.equals("text/plain")) {
            json = false;
        } else {
            return body;
        }

        String suffix = " (DIAL request id: " + traceId + ")";
        String text = body.toString(StandardCharsets.UTF_8);
        if (text.contains(suffix)) {
            return body;
        }
        if (!json) {
            return Buffer.buffer(text.stripTrailing() + suffix);
        }
        JsonNode root = JsonUtil.tryParse(text);
        if (root.get("error") instanceof ObjectNode error && error.get("message") instanceof TextNode message) {
            error.put("message", message.textValue() + suffix);
            return Buffer.buffer(JsonUtil.serialize(root));
        }
        return body;
    }

    private String mediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String type = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
        return type.isEmpty() ? null : type;
    }
}
