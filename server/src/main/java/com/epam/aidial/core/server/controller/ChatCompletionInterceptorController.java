package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseChatCompletionAttachmentsFn;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatCompletionInterceptorController extends BaseInterceptorController {

    private static final Pattern DEPLOYMENT_PATH = Pattern.compile("(/openai/deployments/)([^/]+)(/.*)");

    public ChatCompletionInterceptorController(Proxy proxy, ProxyContext context, int interceptorIndex) {
        super(proxy, context, interceptorIndex);
    }

    @Override
    protected RequestObject parseRequest(Buffer body) throws IOException {
        return new ChatCompletionRequest(ProxyUtil.parseObject(body));
    }

    @Override
    protected String buildUri(ProxyContext context) {
        Deployment deployment = context.getDeployment();
        HttpServerRequest request = context.getRequest();
        String query = request.query();
        String baseUrl = deployment.getInterfaceBaseUrl(InterfaceType.OPENAI_CHAT_COMPLETIONS);
        if (baseUrl != null) {
            // New flow: rewrite the {id} segment to the interceptor's own name, then base_url + path.
            String name = UrlUtil.encodePathSegment(deployment.getName());
            String path = rewriteDeploymentSegment(request.path(), name);
            return query == null ? baseUrl + path : baseUrl + path + "?" + query;
        }
        // Legacy flow: verbatim endpoint + query.
        return query == null ? deployment.getEndpoint() : deployment.getEndpoint() + "?" + query;
    }

    @Override
    protected CollectResponseAttachmentsFn createAttachmentFn(Proxy proxy, ProxyContext context) {
        return new CollectResponseChatCompletionAttachmentsFn(proxy, context);
    }

    @Override
    protected BufferingReadStream.BaseEventListener createListener(Proxy proxy, ProxyContext context) {
        return new DeploymentPostController.ChatCompletionSseListener(new CollectResponseChatCompletionAttachmentsFn(proxy, context));
    }

    static String rewriteDeploymentSegment(String path, String name) {
        Matcher m = DEPLOYMENT_PATH.matcher(path);
        return m.matches() ? m.group(1) + name + m.group(3) : path;
    }
}
