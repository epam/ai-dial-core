package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfacePathMapping;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResponseMetadata;
import com.epam.aidial.core.server.function.AutoShareDeploymentFn;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.BaseResponseFunction;
import com.epam.aidial.core.server.function.CollectRequestStandardAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponsesApiOutputAttachmentsFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

@Slf4j
public class ResponsesInterceptorController extends BaseInterceptorController {

    private final InterfacePathMapping pathMapping;
    private final String dialResponseId;
    private String interceptedResponseId;

    public ResponsesInterceptorController(Proxy proxy, ProxyContext context, int interceptorIndex) {
        super(proxy, context, interceptorIndex, defaultEnhancementFunctions(proxy, context));
        this.pathMapping = null;
        this.dialResponseId = null;
    }

    public ResponsesInterceptorController(Proxy proxy, ProxyContext context, String dialResponseId,
                                          InterfacePathMapping pathMapping, int interceptorIndex) {
        super(proxy, context, interceptorIndex, defaultEnhancementFunctions(proxy, context));
        this.pathMapping = pathMapping;
        this.dialResponseId = dialResponseId;
    }

    private static List<BaseRequestFunction<RequestObject>> defaultEnhancementFunctions(Proxy proxy, ProxyContext context) {
        return List.of(
                new ApplyDefaultDeploymentSettingsFn(proxy, context, InterfaceType.OPENAI_RESPONSES),
                new CollectRequestStandardAttachmentsFn(proxy, context),
                new AutoShareDeploymentFn(proxy, context));
    }

    @Override
    protected RequestObject parseRequest(Buffer body) throws IOException {
        return pathMapping == null ? new ResponsesApiRequest(ProxyUtil.parseObject(body)) : null;
    }

    // an interceptor speaks the DIAL Responses API, so an item hop renders {id} with the dial response id
    @Override
    protected String buildUri(ProxyContext context) {
        Deployment deployment = context.getDeployment();
        HttpServerRequest request = context.getRequest();
        if (pathMapping == null) {
            return DeploymentEndpointUtil.resolveRequestUri(deployment, InterfaceType.OPENAI_RESPONSES,
                    context.getConfig().getTranslators(), request.path(), request.query());
        }
        return DeploymentEndpointUtil.resolveResponseItemUri(deployment, context.getConfig().getTranslators(),
                pathMapping, dialResponseId, request.query());
    }

    @Override
    protected CollectResponseAttachmentsFn createAttachmentFn(Proxy proxy, ProxyContext context) {
        return new CollectResponsesApiOutputAttachmentsFn(proxy, context);
    }

    @Override
    protected BufferingReadStream.BaseEventListener createListener(Proxy proxy, ProxyContext context) {
        if (pathMapping == null) {
            return new ResponsesSseListener(List.of(new ResponseIdExtractorFn(proxy, context),
                    new CollectResponsesApiOutputAttachmentsFn(proxy, context)));
        }
        return new ResponsesSseListener(List.of(new CollectResponsesApiOutputAttachmentsFn(proxy, context)));
    }

    @Override
    protected Future<Void> afterResponse(Buffer responseBody) {
        if (pathMapping != null || interceptedResponseId != null) {
            return Future.succeededFuture();
        }
        JsonNode tree = JsonUtil.tryParse(responseBody.getBytes());
        String responseId = tree.path("id").asText(null);
        return onResponseIdAvailable(responseId);
    }

    @Override
    protected InterfaceType interfaceType() {
        return InterfaceType.OPENAI_RESPONSES;
    }

    private Future<Void> onResponseIdAvailable(String responseId) {
        if (context.isStoreResponse()) {
            ResponseMetadata metadata = ResponseMetadata.builder()
                    .deploymentName(context.getInitialDeployment())
                    .initiatorBucket(BucketBuilder.buildInitiatorBucket(context))
                    .build();
            return proxy.getTaskExecutor()
                    .<Void>submit(() -> {
                        proxy.getResponseMetadataService().saveMetadata(responseId, metadata, EtagHeader.NEW_ONLY);
                        return null;
                    })
                    .recover(exception -> exception instanceof HttpException httpException
                            && httpException.getStatus() == HttpStatus.PRECONDITION_FAILED
                            ? Future.succeededFuture()
                            : Future.failedFuture(exception))
                    .onFailure(e -> log.warn("Failed to save response metadata for interceptor response {}", responseId, e));
        }

        return Future.succeededFuture();
    }

    private class ResponseIdExtractorFn extends BaseResponseFunction {
        ResponseIdExtractorFn(Proxy proxy, ProxyContext context) {
            super(proxy, context);
        }

        @Override
        public Future<JsonNode> apply(JsonNode tree) {
            Future<JsonNode> result = Future.succeededFuture(tree);
            if (tree.get("response") instanceof ObjectNode response) {
                JsonNode idNode = response.path("id");
                if (idNode.isTextual()) {
                    boolean firstTime = interceptedResponseId == null;
                    interceptedResponseId = idNode.asText();
                    if (firstTime) {
                        result = onResponseIdAvailable(interceptedResponseId).map(tree);
                    }
                }
            }
            return result;
        }
    }
}
