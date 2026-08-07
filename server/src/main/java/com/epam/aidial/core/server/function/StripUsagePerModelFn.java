package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.UsagePerModelInjector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

/**
 * Strips any {@code statistics.usage_per_model} an upstream deployment already streamed, so Core's
 * own value - appended as a separate chunk right before the streaming terminator, see
 * {@code DeploymentPostController} - is the only one a client ever sees. Without this, an app's own
 * streamed entries would positionally merge with Core's under MergeChunks' indexed-array semantics
 * instead of being overridden by them.
 */
public class StripUsagePerModelFn extends BaseResponseFunction {

    public StripUsagePerModelFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        if (tree instanceof ObjectNode object) {
            UsagePerModelInjector.strip(object);
        }
        return Future.succeededFuture(tree);
    }
}
