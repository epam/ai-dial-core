package com.epam.aidial.core.server.log;

import javax.annotation.Nullable;

/**
 * An inline file found in a body. Offsets are byte positions in the body.
 *
 * @param valueStart   the opening quote of the JSON string.
 * @param payloadStart the first payload byte: after the data URL prefix, or after the opening quote.
 * @param payloadEnd   the closing quote, or -1 when the string is still open at the cut.
 * @param chunk        the ordinal of the SSE event, null in a JSON body.
 * @param path         JSON Pointer of the value, relative to the event's JSON in an SSE body.
 * @param mime         null when unknown.
 */
record InlineFile(int valueStart, int payloadStart, int payloadEnd, @Nullable Integer chunk, String path, boolean dataUrl,
                  @Nullable String mime) {

    boolean crossesCut() {
        return payloadEnd < 0;
    }
}
