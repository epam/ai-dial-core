package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
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

import java.io.IOException;
import java.util.List;

public class ResponsesInterceptorController extends BaseInterceptorController {

    private final String uriSuffix;

    public ResponsesInterceptorController(Proxy proxy, ProxyContext context, int interceptorIndex) {
        super(proxy, context, interceptorIndex, defaultEnhancementFunctions(proxy, context));
        this.uriSuffix = "";
    }

    public ResponsesInterceptorController(Proxy proxy, ProxyContext context, String dialResponseId, String operationSuffix, int interceptorIndex) {
        super(proxy, context, interceptorIndex, defaultEnhancementFunctions(proxy, context));
        this.uriSuffix = "/" + dialResponseId + operationSuffix;
    }

    private static List<BaseRequestFunction<RequestObject>> defaultEnhancementFunctions(Proxy proxy, ProxyContext context) {
        return List.of(
                new ApplyDefaultDeploymentSettingsFn(proxy, context),
                new CollectRequestStandardAttachmentsFn(proxy, context),
                new AutoShareDeploymentFn(proxy, context));
    }

    @Override
    protected RequestObject parseRequest(Buffer body) throws IOException {
        return uriSuffix.isEmpty() ? new ResponsesApiRequest(ProxyUtil.parseObject(body)) : null;
    }

    @Override
    protected String buildUri(ProxyContext context) {
        Deployment deployment = context.getDeployment();
        String uri = DeploymentEndpointUtil.responsesBaseUri(deployment) + uriSuffix;
        String query = context.getRequest().query();
        return query == null ? uri : uri + "?" + query;
    }

    @Override
    protected CollectResponseAttachmentsFn createAttachmentFn(Proxy proxy, ProxyContext context) {
        return new CollectResponsesApiOutputAttachmentsFn(proxy, context);
    }

    @Override
    protected BufferingReadStream.BaseEventListener createListener(Proxy proxy, ProxyContext context) {
        return new ResponsesSseListener(List.of(new CollectResponsesApiOutputAttachmentsFn(proxy, context)));
    }
}
