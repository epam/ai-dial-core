package com.epam.aidial.core.server.layout;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A checked-in, deterministic sequence of API calls. Scenarios are data rather than code so that one corpus
 * feeds both runs by construction — a hand-written pair of tests could drift.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Scenario(String name, String description, List<Step> steps) {

    /**
     * One request. {@code path}, {@code query}, {@code body} and header values go through placeholder
     * substitution: {@code ${bucket1}} and {@code ${bucket2}} are the buckets of the two api keys, and any
     * name captured by an earlier step is available under its own name.
     *
     * @param capture        variable name to a place in this step's response body; lets a later step address
     *                       something the server generated, such as a publication url
     * @param captureHeaders variable name to response header name — how a conditional request gets the etag
     *                       of the write that preceded it
     * @param multipart      set to upload the body as a file rather than send it as the request entity
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Step(String name,
                       String method,
                       String path,
                       String query,
                       Object body,
                       Map<String, String> headers,
                       Map<String, Capture> capture,
                       Map<String, String> captureHeaders,
                       Multipart multipart) {

        public Map<String, String> headersOrEmpty() {
            return headers == null ? Map.of() : headers;
        }

        public Map<String, Capture> captureOrEmpty() {
            return capture == null ? Map.of() : capture;
        }

        public Map<String, String> captureHeadersOrEmpty() {
            return captureHeaders == null ? Map.of() : captureHeaders;
        }
    }

    /**
     * Where a variable's value comes from.
     *
     * @param at      JSON pointer into the response body
     * @param extract optional regular expression applied to the pointed-at value; group 1 becomes the
     *                variable. An invitation link carries the id the response never returns on its own.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Capture(String at, String extract) {
    }
}
