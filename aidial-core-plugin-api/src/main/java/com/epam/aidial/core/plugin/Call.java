package com.epam.aidial.core.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;

/**
 * A request a plugin sends to Core through {@link HostDelegate#call}, as it would send it to Core over HTTP.
 *
 * @param method  the request's method
 * @param uri     the path and query of a route of Core's API: a create operation, such as a deployments-POST action
 *                ({@code /openai/deployments/<name>/chat/completions}), {@code /openai/v1/chat/completions},
 *                {@code /openai/v1/responses}, {@code /openai/responses} or {@code /anthropic/v1/messages} (the last
 *                four name the deployment in the body's {@code model}; a Responses call with {@code background: true}
 *                is refused), or any other route, such as {@code /v1/user/info} or a resource's
 * @param headers the request's headers. Core copies them, and drops the ones it sets for an origin itself
 *                ({@code X-UPSTREAM-ENDPOINT}, {@code X-UPSTREAM-KEY}, {@code X-UPSTREAM-EXTRA-DATA},
 *                {@code X-DIAL-CACHE-BREAKPOINT-PATH}, {@code X-DIAL-CACHE-EXTRA-METADATA},
 *                {@code X-DIAL-OVERRIDE-NAME}, {@code X-DIAL-DEPLOYMENT-ID}, {@code X-DIAL-APPLICATION-ID},
 *                {@code X-DIAL-APPLICATION-PROPERTIES} and {@code X-DIAL-DEPLOYMENT-FEATURES}) and
 *                {@code Content-Length}, so a plugin may pass on its own request's headers. The call carries the
 *                per-request key of the plugin's request
 * @param body    the request's JSON body, or null for none; a create operation needs one. Core takes it: the called
 *                deployment's processing changes it in place, so a plugin that calls twice with one body passes a
 *                {@code deepCopy()}
 */
public record Call(HttpMethod method, String uri, MultiMap headers, ObjectNode body) {

    /**
     * A {@code POST}.
     */
    public Call(String uri, MultiMap headers, ObjectNode body) {
        this(HttpMethod.POST, uri, headers, body);
    }
}
