package com.epam.aidial.core.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;

/**
 * A request a plugin sends to Core through {@link HostDelegate#call}: a {@code POST} to a create operation with a body,
 * or a {@code GET} of {@code /v1/user/info} without one.
 *
 * @param method  {@code POST} or {@code GET}
 * @param uri     for a {@code POST}, the path and query of a create operation: a deployments-POST action such as
 *                {@code /openai/deployments/<name>/chat/completions}, {@code /openai/v1/chat/completions},
 *                {@code /openai/v1/responses} or {@code /openai/responses}, or {@code /anthropic/v1/messages}. The
 *                last four name the deployment in the body's {@code model}. A Responses call with
 *                {@code background: true} is refused. For a {@code GET}, {@code /v1/user/info}
 * @param headers the request's headers. Core copies them, and drops the ones it sets for an origin itself
 *                ({@code X-UPSTREAM-ENDPOINT}, {@code X-UPSTREAM-KEY}, {@code X-UPSTREAM-EXTRA-DATA},
 *                {@code X-DIAL-CACHE-BREAKPOINT-PATH}, {@code X-DIAL-CACHE-EXTRA-METADATA},
 *                {@code X-DIAL-OVERRIDE-NAME}, {@code X-DIAL-DEPLOYMENT-ID}, {@code X-DIAL-APPLICATION-ID},
 *                {@code X-DIAL-APPLICATION-PROPERTIES} and {@code X-DIAL-DEPLOYMENT-FEATURES}), so a plugin may pass on
 *                its own request's headers. The call carries the per-request key of the plugin's request
 * @param body    the body of a {@code POST}; null for a {@code GET}. Core takes it: the called deployment's processing
 *                changes it in place, so a plugin that calls twice with one body passes a {@code deepCopy()}
 */
public record Call(HttpMethod method, String uri, MultiMap headers, ObjectNode body) {

    /**
     * A {@code POST}.
     */
    public Call(String uri, MultiMap headers, ObjectNode body) {
        this(HttpMethod.POST, uri, headers, body);
    }
}
