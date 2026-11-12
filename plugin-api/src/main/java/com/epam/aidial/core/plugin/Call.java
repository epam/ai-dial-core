package com.epam.aidial.core.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;

/**
 * A {@code POST} a plugin sends to a deployment through {@link HostDelegate#call}.
 *
 * @param uri     the path and query of a create operation: a deployments-POST action such as
 *                {@code /openai/deployments/<name>/chat/completions}, {@code /openai/v1/chat/completions},
 *                {@code /openai/v1/responses} or {@code /openai/responses}, or {@code /anthropic/v1/messages}. The
 *                last four name the deployment in the body's {@code model}. A Responses call with
 *                {@code background: true} is refused
 * @param headers the request's headers. Core copies them, and drops the ones it sets for an origin itself
 *                ({@code X-UPSTREAM-ENDPOINT}, {@code X-UPSTREAM-KEY}, {@code X-UPSTREAM-EXTRA-DATA},
 *                {@code X-DIAL-CACHE-BREAKPOINT-PATH}, {@code X-DIAL-CACHE-EXTRA-METADATA},
 *                {@code X-DIAL-OVERRIDE-NAME}, {@code X-DIAL-DEPLOYMENT-ID}, {@code X-DIAL-APPLICATION-ID},
 *                {@code X-DIAL-APPLICATION-PROPERTIES} and {@code X-DIAL-DEPLOYMENT-FEATURES}), so a plugin may pass on
 *                its own request's headers. The call carries the per-request key of the plugin's request
 * @param body    the request's body. Core takes it: the called deployment's processing changes it in place, so a
 *                plugin that calls twice with one body passes a {@code deepCopy()}
 */
public record Call(String uri, MultiMap headers, ObjectNode body) {
}
