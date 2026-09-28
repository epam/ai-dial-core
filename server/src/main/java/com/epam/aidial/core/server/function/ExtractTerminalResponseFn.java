package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;

import javax.annotation.Nullable;

public class ExtractTerminalResponseFn extends BaseResponseFunction {
    @Nullable
    private JsonNode terminalResponse;

    public ExtractTerminalResponseFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        String type = tree.path("type").asText(null);
        if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
            JsonNode responseNode = tree.get("response");
            if (responseNode != null) {
                terminalResponse = responseNode;
                context.setPricingUsageNode(responseNode);
            }
        }
        return Future.succeededFuture(tree);
    }

    /**
     * Serialized on demand: a caller that only needs {@code pricingUsageNode} never pays for it.
     */
    @Nullable
    public String getAssembledStreamingResponse() {
        return terminalResponse == null ? null : ProxyUtil.convertToString(terminalResponse);
    }
}
