package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.OverridePathKey;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.AutoShareDeploymentFn;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.CollectRequestStandardAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponsesApiOutputAttachmentsFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;

import java.io.IOException;
import java.util.List;

public class ResponsesInterceptorController extends BaseInterceptorController {

    private final OverridePathKey pathKey;
    private final String dialResponseId;

    public ResponsesInterceptorController(Proxy proxy, ProxyContext context, int interceptorIndex) {
        super(proxy, context, interceptorIndex, defaultEnhancementFunctions(proxy, context));
        this.pathKey = null;
        this.dialResponseId = null;
    }

    public ResponsesInterceptorController(Proxy proxy, ProxyContext context, String dialResponseId,
                                          OverridePathKey pathKey, int interceptorIndex) {
        super(proxy, context, interceptorIndex, defaultEnhancementFunctions(proxy, context));
        this.pathKey = pathKey;
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
        return pathKey == null ? new ResponsesApiRequest(ProxyUtil.parseObject(body)) : null;
    }

    // an interceptor speaks the DIAL Responses API, so an item hop renders {id} with the dial response id
    @Override
    protected String buildUri(ProxyContext context) {
        Deployment deployment = context.getDeployment();
        HttpServerRequest request = context.getRequest();
        if (pathKey == null) {
            return DeploymentEndpointUtil.resolveRequestUri(deployment, InterfaceType.OPENAI_RESPONSES,
                    context.getConfig().getTranslators(), request.path(), request.query());
        }
        return DeploymentEndpointUtil.resolveResponseItemUri(deployment, context.getConfig().getTranslators(),
                pathKey, dialResponseId, request.query());
    }

    @Override
    protected CollectResponseAttachmentsFn createAttachmentFn(Proxy proxy, ProxyContext context) {
        return new CollectResponsesApiOutputAttachmentsFn(proxy, context);
    }

    @Override
    protected BufferingReadStream.BaseEventListener createListener(Proxy proxy, ProxyContext context) {
        return new ResponsesSseListener(List.of(new CollectResponsesApiOutputAttachmentsFn(proxy, context)));
    }

    @Override
    protected InterfaceType interfaceType() {
        return InterfaceType.OPENAI_RESPONSES;
    }
}
