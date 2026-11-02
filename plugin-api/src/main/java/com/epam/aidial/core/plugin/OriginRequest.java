package com.epam.aidial.core.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;

/**
 * The request Core would send to an HTTP origin for the deployment.
 *
 * @param method   the client's method
 * @param uri      the path and query Core would append to the deployment's base url, for example
 *                 {@code /openai/deployments/app/chat/completions?api-version=1}
 * @param headers  the headers Core would send, the per-request key in {@code API-KEY} and, for a model with upstreams,
 *                 the {@code X-UPSTREAM-*} headers of the chosen upstream included. Core forwards the client's headers
 *                 too, so {@code X-UPSTREAM-*} comes from Core only for a model with upstreams; elsewhere the client
 *                 may have set it
 * @param body     the body Core would send, empty for a request without one. Read-only: Core reuses it for a retry,
 *                 the analytics row and the limits
 * @param bodyTree {@code body} as a parsed JSON object; null for a request whose body Core does not parse, such as a
 *                 Responses item operation. Read-only, unless the plugin hands it to {@link OriginHost#call}, which
 *                 takes it
 * @param caller   who sent the request
 */
public record OriginRequest(HttpMethod method, String uri, MultiMap headers, Buffer body,
                            ObjectNode bodyTree, OriginCaller caller) {
}
