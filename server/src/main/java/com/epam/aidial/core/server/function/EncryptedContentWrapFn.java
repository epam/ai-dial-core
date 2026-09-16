package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.EncryptedContentAffinityUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

/**
 * Stamps streaming Responses API output items with the serving upstream's config id, so a client-echoed
 * encrypted item on a later turn can be routed back to the same upstream. See
 * {@link EncryptedContentAffinityUtil}.
 */
public class EncryptedContentWrapFn extends BaseResponseFunction {

    private final String explicitUpstreamConfigId;

    public EncryptedContentWrapFn(Proxy proxy, ProxyContext context) {
        this(proxy, context, null);
    }

    public EncryptedContentWrapFn(Proxy proxy, ProxyContext context, String explicitUpstreamConfigId) {
        super(proxy, context);
        this.explicitUpstreamConfigId = explicitUpstreamConfigId;
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        if (!EncryptedContentAffinityUtil.hasConfiguredUpstreams(context.getDeployment())) {
            return Future.succeededFuture(tree);
        }
        String upstreamConfigId = explicitUpstreamConfigId != null
                ? explicitUpstreamConfigId
                : context.getUpstreamRoute().get().getId();

        if (tree.get("item") instanceof ObjectNode item && "response.output_item.done".equals(tree.path("type").asText())) {
            EncryptedContentAffinityUtil.wrapOutputItem(item, upstreamConfigId);
        } else if (tree.get("response") instanceof ObjectNode response) {
            EncryptedContentAffinityUtil.wrapOutputArray(response.path("output"), upstreamConfigId);
        }
        return Future.succeededFuture(tree);
    }
}
