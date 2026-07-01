package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.AutoShareDeploymentFn;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.CollectRequestChatCompletionAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseChatCompletionAttachmentsFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class InterceptorController extends BaseDeploymentPostController {

    private static final Pattern DEPLOYMENT_PATH = Pattern.compile("(/openai/deployments/)([^/]+)(/.*)");

    private final List<BaseRequestFunction<RequestObject>> enhancementFunctions;

    public InterceptorController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.enhancementFunctions = List.of(new ApplyDefaultDeploymentSettingsFn(proxy, context),
                new CollectRequestChatCompletionAttachmentsFn(proxy, context),
                new AutoShareDeploymentFn(proxy, context));
    }

    public Future<?> handle() {
        log.info("Received request from client. Deployment: {}. Headers: {}",
                context.getDeployment().getName(),
                context.getRequest().headers().size());

        return proxy.getTokenStatsTracker().startSpan(context).map(ignore -> {
            context.getRequest().body()
                    .onSuccess(body -> proxy.getTaskExecutor().submit(() -> {
                        handleRequestBody(body);
                        return null;
                    }).onFailure(this::handleError))
                    .onFailure(this::handleRequestBodyError);
            return null;
        });
    }

    private void handleError(Throwable error) {
        log.error("Can't handle request. Error: {}", error.getMessage());
        respond(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private void handleRequestBody(Buffer requestBody) {
        context.setRequestBody(requestBody);
        context.setRequestBodyTimestamp(System.currentTimeMillis());
        try {
            RequestObject request = new ChatCompletionRequest(ProxyUtil.parseObject(requestBody));
            context.setStreamingRequest(request.isStreaming());
            if (ProxyUtil.processChain(request, enhancementFunctions)) {
                context.setRequestBody(Buffer.buffer(request.serialize()));
            }
            proxy.getApiKeyStore().assignPerRequestApiKey(context.getProxyApiKeyData());
        } catch (Throwable e) {
            if (e instanceof HttpException httpException) {
                respond(httpException.getStatus(), httpException.getMessage());
            } else {
                respond(HttpStatus.BAD_REQUEST);
            }
            log.warn("Can't process JSON request body.  Error:", e);
            return;
        }
        sendRequest();
    }


    private void sendRequest() {
        createProxyRequest(interceptorUri())
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private String interceptorUri() {
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
        // Legacy flow: verbatim endpoint + query — identical to today's buildUri.
        return query == null ? deployment.getEndpoint() : deployment.getEndpoint() + "?" + query;
    }

    static String rewriteDeploymentSegment(String path, String name) {
        Matcher m = DEPLOYMENT_PATH.matcher(path);
        return m.matches() ? m.group(1) + name + m.group(3) : path;
    }


    void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to interceptor. Deployment: {}. Address: {}",
                 context.getDeployment().getName(), proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers());

        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());

        proxyRequest.putHeader(Proxy.HEADER_DEPLOYMENT_ID, context.getInitialDeployment());

        Buffer requestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(requestBody.length()));

        proxyRequest.send(requestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyResponseError);
    }

    /**
     * Called when proxy failed to receive response header from origin.
     */
    private void handleProxyResponseError(Throwable error) {
        log.warn("Proxy failed to receive response header from origin. Address: {}. Error:",
                context.getProxyRequest().connection().remoteAddress(),
                error);
    }

    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        log.info("Received header from origin. Endpoint: {}. Status: {}. Headers: {}",
                context.getDeployment().getEndpoint(),
                proxyResponse.statusCode(), proxyResponse.headers().size());

        Supplier<BufferingReadStream.BaseEventListener> eventListenerSupplier = () ->
                new DeploymentPostController.ChatCompletionSseListener(new CollectResponseChatCompletionAttachmentsFn(proxy, context));
        BufferingReadStream responseStream = createResponseStream(proxyResponse, eventListenerSupplier);

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseTimestamp(System.currentTimeMillis());

        HttpServerResponse response = context.getResponse();

        ProxyUtil.handleChunkedResponse(response, proxyResponse);

        responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignore -> handleResponse(responseStream))
                .onFailure(this::handleResponseError);
    }

    void handleResponse(BufferingReadStream responseStream) {
        Buffer responseBody = responseStream.getContent();
        CollectResponseChatCompletionAttachmentsFn fn = new CollectResponseChatCompletionAttachmentsFn(proxy, context);
        collectResponseAttachments(responseBody, fn).onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to collect attachments from response. Error:", result.cause());
            }
            completeProxyResponse(responseStream);
        });
    }

    private void completeProxyResponse(BufferingReadStream responseStream) {
        HttpServerResponse response = context.getResponse();
        responseStream.end(response);
        finalizeRequest();
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client. Error:", error);

        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        finalizeRequest();
    }

}
