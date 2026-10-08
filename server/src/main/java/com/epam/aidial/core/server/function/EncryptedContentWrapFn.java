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

    private final String explicitEncryptedUpstreamId;

    public EncryptedContentWrapFn(Proxy proxy, ProxyContext context) {
        this(proxy, context, null);
    }

    public EncryptedContentWrapFn(Proxy proxy, ProxyContext context, String explicitEncryptedUpstreamId) {
        super(proxy, context);
        this.explicitEncryptedUpstreamId = explicitEncryptedUpstreamId;
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        if (!EncryptedContentAffinityUtil.hasConfiguredUpstreams(context.getDeployment())) {
            return Future.succeededFuture(tree);
        }
        String encryptedUpstreamId = explicitEncryptedUpstreamId != null
                ? explicitEncryptedUpstreamId
                : context.getUpstreamRoute().get().getId();
        String deploymentName = context.getDeployment().getName();

        String type = tree.path("type").asText();
        if (tree.get("item") instanceof ObjectNode item
                && ("response.output_item.done".equals(type) || "response.output_item.added".equals(type))) {
            EncryptedContentAffinityUtil.wrapOutputItem(item, encryptedUpstreamId, deploymentName);
        } else if (tree.get("response") instanceof ObjectNode response) {
            EncryptedContentAffinityUtil.wrapOutputArray(response.path("output"), encryptedUpstreamId, deploymentName);
        } else if (tree instanceof ObjectNode event
                && ("response.reasoning_summary_part.added".equals(type)
                        || "response.reasoning_summary_text.delta".equals(type)
                        || "response.reasoning_summary_text.done".equals(type)
                        || "response.reasoning_summary_part.done".equals(type))) {
            EncryptedContentAffinityUtil.wrapSseItemId(event, encryptedUpstreamId, deploymentName);
        }
        return Future.succeededFuture(tree);
    }
}
