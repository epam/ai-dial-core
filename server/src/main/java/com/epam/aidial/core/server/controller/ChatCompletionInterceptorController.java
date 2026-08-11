package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.AutoShareDeploymentFn;
import com.epam.aidial.core.server.function.CollectRequestStandardAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseChatCompletionAttachmentsFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.enhancement.EnhanceDeploymentRequestFn;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerRequest;

import java.io.IOException;
import java.util.List;

public class ChatCompletionInterceptorController extends BaseInterceptorController {

    public ChatCompletionInterceptorController(Proxy proxy, ProxyContext context, int interceptorIndex) {
        super(proxy, context, interceptorIndex, List.of(
                new ApplyDefaultDeploymentSettingsFn(proxy, context),
                new EnhanceDeploymentRequestFn(proxy, context),
                new CollectRequestStandardAttachmentsFn(proxy, context),
                new AutoShareDeploymentFn(proxy, context)));
    }

    @Override
    protected void enrichProxyRequestHeaders(HttpClientRequest proxyRequest) {
        ProxyUtil.setOverrideNameHeader(proxyRequest, context.getDeployment());
    }

    @Override
    protected RequestObject parseRequest(Buffer body) throws IOException {
        return new ChatCompletionRequest(ProxyUtil.parseObject(body));
    }

    @Override
    protected String buildUri(ProxyContext context) {
        HttpServerRequest request = context.getRequest();
        // the deployment here is the interceptor itself, so the {id} segment names the interceptor
        return DeploymentEndpointUtil.requestUri(context.getDeployment(),
                InterfaceType.OPENAI_CHAT_COMPLETIONS, request.path(), request.query());
    }

    @Override
    protected CollectResponseAttachmentsFn createAttachmentFn(Proxy proxy, ProxyContext context) {
        return new CollectResponseChatCompletionAttachmentsFn(proxy, context);
    }

    @Override
    protected BufferingReadStream.BaseEventListener createListener(Proxy proxy, ProxyContext context) {
        return new DeploymentPostController.ChatCompletionSseListener(List.of(new CollectResponseChatCompletionAttachmentsFn(proxy, context)));
    }
}
