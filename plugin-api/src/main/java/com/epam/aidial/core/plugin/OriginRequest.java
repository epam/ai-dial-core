package com.epam.aidial.core.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;

/**
 * The request Core would send to an HTTP origin for the deployment, or, for a router in decision mode whose interface
 * the plugin serves, the {@code POST /decide} request Core would send to the router: a decide request, or a notice
 * with {@code X-DIAL-ROUTER-NOTICE: true}. Neither carries a {@code traceparent}: Core starts no client span for a
 * plugin.
 *
 * @param method   the client's method
 * @param uri      the path and query Core would append to the deployment's base url, for example
 *                 {@code /openai/deployments/app/chat/completions?api-version=1}, or {@code /decide}
 * @param headers  the headers Core would send, the per-request key in {@code API-KEY} and, for a model with upstreams,
 *                 the {@code X-UPSTREAM-*} headers of the chosen upstream included. Core forwards the client's headers
 *                 too, so {@code X-UPSTREAM-*} comes from Core only for a model with upstreams; elsewhere the client
 *                 may have set it. A decide request carries the decide request's key in {@code API-KEY}; a notice
 *                 carries none
 * @param body     the body Core would send, empty for a request without one. Read-only: Core reuses it for a retry,
 *                 the analytics row and the limits. A decide request carries the client's body only when the router
 *                 declares {@code decision.body: true}, and it is the client's own buffer, which Core reuses for the
 *                 deployments it then serves
 * @param bodyTree {@code body} as a parsed JSON object; null for a request whose body Core does not parse, such as a
 *                 Responses item operation, or a decide request without the body. Read-only, unless the plugin hands
 *                 it to {@link OriginHost#call}, which takes it. A decide request's tree is the plugin's own copy, which
 *                 it may change
 * @param caller   who sent the request
 */
public record OriginRequest(HttpMethod method, String uri, MultiMap headers, Buffer body,
                            ObjectNode bodyTree, OriginCaller caller) {
}
