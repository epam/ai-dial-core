package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

public class ReplaceResponseIdFn extends BaseResponseFunction {

    private String upstreamId;

    public ReplaceResponseIdFn(Proxy proxy, ProxyContext context, String upstreamId) {
        super(proxy, context);
        this.upstreamId = upstreamId;
    }

    public ReplaceResponseIdFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        if (!(tree instanceof ObjectNode object)
                || !(object.get("response") instanceof ObjectNode response)) {
            return Future.succeededFuture(tree);
        }

        JsonNode idNode = response.path("id");
        if (!idNode.isTextual()) {
            return Future.succeededFuture(tree);
        }

        String currentId = idNode.asText();

        if (upstreamId == null) {
            upstreamId = currentId;
            return saveIdMapping(response, tree);
        }

        if (upstreamId.equals(currentId)) {
            response.put("id", context.getDialResponseId());
        }

        return Future.succeededFuture(tree);
    }

    private Future<JsonNode> saveIdMapping(ObjectNode response, JsonNode tree) {
        if (!context.isStoreResponse()) {
            String dialId = ResponseIdUtil.createResponseId(context.getDeployment().getName(), proxy.getGenerator().get());
            context.setDialResponseId(dialId);
            response.put("id", dialId);
            return Future.succeededFuture(tree);
        }
        Upstream upstream = context.getUpstreamRoute().get();
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId(upstreamId)
                .upstreamKey(upstream.getId())
                .deploymentName(context.getDeployment().getName())
                .initiatorBucket(BucketBuilder.buildInitiatorBucket(context))
                .build();
        return proxy.getTaskExecutor()
                .submit(() -> proxy.getResponseMappingService().saveMapping(context, mapping))
                .compose(dialId -> {
                    context.setDialResponseId(dialId);
                    response.put("id", dialId);

                    if (!context.isBackgroundJob()) {
                        return Future.succeededFuture();
                    }
                    return proxy.getBackgroundJobService().saveJob(context);
                })
                .map(tree);
    }
}
