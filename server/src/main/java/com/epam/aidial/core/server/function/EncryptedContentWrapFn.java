package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.EncryptedAffinityUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import lombok.Getter;

/**
 * Stamps streaming Responses API output items with the serving upstream's config id, so a client-echoed
 * encrypted item on a later turn can be routed back to the same upstream. See
 * {@link EncryptedAffinityUtil}.
 *
 * <p>Also replaces the top-level {@code response.id} with a DIAL-encoded id that embeds the
 * upstream config id and original provider id, enabling affinity-aware routing on
 * {@code GET /responses/{id}} without a storage lookup.
 */
public class EncryptedContentWrapFn extends BaseResponseFunction {
    private final String upstreamId;
    @Getter
    private String dialId;

    public EncryptedContentWrapFn(Proxy proxy, ProxyContext context, String upstreamId) {
        super(proxy, context);
        this.upstreamId = upstreamId;
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        if (tree.get("item") instanceof ObjectNode item
                && "response.output_item.done".equals(tree.path("type").asText())) {
            EncryptedAffinityUtil.wrapOutputItem(item, upstreamId);
        } else if (tree.get("response") instanceof ObjectNode response) {
            EncryptedAffinityUtil.wrapOutputArray(response.path("output"), upstreamId);

            JsonNode idNode = response.path("id");
            if (idNode.isTextual()) {
                String currentId = idNode.asText();
                dialId = EncryptedAffinityUtil.wrapResponseId(upstreamId, currentId, context.getDeployment().getName());
                response.put("id", dialId);
            }
        }

        return Future.succeededFuture(tree);
    }
}
