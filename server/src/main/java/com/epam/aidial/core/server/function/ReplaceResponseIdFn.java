package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

public class ReplaceResponseIdFn extends BaseResponseFunction {

    private final String dialId;
    private String upstreamId;

    public ReplaceResponseIdFn(Proxy proxy, ProxyContext context, String dialId) {
        this(proxy, context, dialId, null);
    }

    public ReplaceResponseIdFn(Proxy proxy, ProxyContext context, String dialId, String upstreamId) {
        super(proxy, context);
        this.dialId = dialId;
        this.upstreamId = upstreamId;
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        if (!(tree instanceof ObjectNode object)
                || !(object.get("response") instanceof ObjectNode response)) {
            return Future.succeededFuture(tree);
        }

        JsonNode idNode = response.path("id");
        if (idNode.isNull()) {
            return Future.succeededFuture(tree);
        }

        String currentId = idNode.asText();

        Future<JsonNode> result;
        if (upstreamId == null) {
            upstreamId = currentId;
            result = saveIdMapping().map(tree);
        } else {
            result = Future.succeededFuture(tree);
        }

        if (upstreamId.equals(currentId)) {
            response.put("id", dialId);
        }

        return result;
    }

    private Future<Void> saveIdMapping() {
        if (!context.isBackground()) {
            return Future.succeededFuture();
        }

        Upstream upstream = context.getUpstreamRoute().get();
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId(upstreamId)
                .upstreamKey(upstream.toStickyKey())
                .deploymentName(context.getDeployment().getName())
                .initiatorBucket(BucketBuilder.buildInitiatorBucket(context))
                .build();
        ResourceDescriptor descriptor = ResponseIdUtil.getDescriptor(dialId);
        return proxy.getTaskExecutor().submit(() -> {
            proxy.getResponseMappingService().saveMapping(descriptor, mapping);
            return null;
        });
    }
}
