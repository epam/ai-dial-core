package com.epam.aidial.core.server.sse;

import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * Server side event parser.
 *
 * <p>
 *     Note. The class is not thread-safe since the parser processes all chunks sequentially.
 * </p>
 */
@Slf4j
public class SseParser {

    private static final byte[] BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final SseEventListener listener;
    private final ByteBuf chunkBuffer;

    // Current event state
    private final StringBuilder dataBuffer = new StringBuilder();
    private String eventType = null;
    private String lastEventId = null;
    private Integer retry = null;

    private boolean firstChunk = true;

    public SseParser(int initialChunkBufferSize, SseEventListener listener) {
        this.listener = listener;
        chunkBuffer = ByteBufAllocator.DEFAULT.heapBuffer(initialChunkBufferSize, Integer.MAX_VALUE);
    }

    public void parse(Buffer chunk) {
        int len = chunk.length();
        try {
            for (int i = skipBom(chunk); i < len; i++) {
                byte b = chunk.getByte(i);
                if (b == '\r') {
                    if (i + 1 < len && chunk.getByte(i + 1) == '\n') {
                        i++;
                    }
                    processLine();
                } else if (b == '\n') {
                    processLine();
                } else {
                    // accumulate buffer
                    chunkBuffer.writeByte(b);
                }
            }
        } catch (Throwable error) {
            log.error("Error occurred at parsing chunk", error);
        }
    }

    /**
     * Call this when the stream is known to be finished/closed.
     * It will flush any remaining partial line and event.
     */
    public void finish() {
        try {
            processLine();
            // Emit pending event if any
            emitEventIfNeeded();
            listener.onComplete();
        } catch (Throwable error) {
            log.error("Error occurred at finishing SSE stream", error);
        }
    }

    /**
     * Releases the pooled buffer backing this parser. Must be called exactly once when the parser is no longer used.
     */
    public void close() {
        chunkBuffer.release();
    }

    @VisibleForTesting
    int chunkBufferRefCnt() {
        return chunkBuffer.refCnt();
    }

    private void processLine() {
        String line = bufferToString();
        chunkBuffer.clear();

        if (line.isEmpty()) {
            emitEventIfNeeded();
            resetEvent();
            return;
        }
        // Comment line starting with ":"
        if (line.charAt(0) == ':') {
            listener.onComment(line.substring(1));
            // Ignore comment
            return;
        }

        // Parse "field: value"
        String field;
        String value;

        int colonIndex = line.indexOf(':');
        if (colonIndex == -1) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, colonIndex);
            value = line.substring(colonIndex + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
        }

        switch (field) {
            case "data":
                if (!dataBuffer.isEmpty()) {
                    dataBuffer.append('\n');
                }
                dataBuffer.append(value);
                break;
            case "event":
                eventType = value;
                break;
            case "id":
                // Ignore if contains NUL
                if (value.indexOf('\0') == -1) {
                    lastEventId = value;
                }
                break;
            case "retry":
                try {
                    retry = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    // ignore non-integer retry
                }
                break;
            default:
                // unknown field: ignore or extend Event if you need to store them
                break;
        }
    }

    private void emitEventIfNeeded() {
        // Skip if nothing meaningful
        if (dataBuffer.isEmpty() && eventType == null && lastEventId == null && retry == null) {
            return;
        }

        String type = (eventType != null) ? eventType : "message";
        String data = dataBuffer.toString();
        SseEvent event = new SseEvent(type, data, lastEventId, retry);
        try {
            listener.onEvent(event);
        } catch (Throwable e) {
            // ignored
        }
    }

    /**
     * Reset event-scoped fields.
     * Note: lastEventId and retry are client state and are kept.
     */
    private void resetEvent() {
        dataBuffer.setLength(0);
        eventType = null;
        // lastEventId and retry intentionally persist
    }


    private String bufferToString() {
        return StandardCharsets.UTF_8.decode(chunkBuffer.nioBuffer()).toString();
    }

    /**
     * <a href="https://en.wikipedia.org/wiki/Byte_order_mark">BOM</a>
     */
    private int skipBom(Buffer chunk) {
        if (!firstChunk) {
            return 0;
        }
        firstChunk = false;
        if (chunk.length() < BOM.length) {
            return 0;
        }
        for (int i = 0; i < BOM.length; i++) {
            if (chunk.getByte(i) != BOM[i]) {
                return 0;
            }
        }
        return BOM.length;
    }
}
