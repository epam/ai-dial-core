package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.controller.ResponsesController;
import com.epam.aidial.core.server.function.CollectMessagesTokenUsageFn;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.token.MessagesTokenUsageParser;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.util.List;

/**
 * Anthropic Messages API pass-through controller. Mirrors {@link ResponsesController} but forwards
 * the body verbatim (no server-side store, no response-id rewrite) and accounts token usage with
 * Anthropic semantics (see {@link MessagesTokenUsageParser} / {@link CollectMessagesTokenUsageFn}).
 */
@Slf4j
public class MessagesController extends MessagesBaseController {

    public MessagesController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    public Future<?> handle() {
        String contentType = context.getRequest().getHeader(HttpHeaders.CONTENT_TYPE);
        if (!Strings.CI.contains(contentType, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
        }
        context.getRequest().body()
                .map(MessagesBaseController::parseBody)
                .compose(request -> {
                    String model = request.getModel();
                    return proxy.getTaskExecutor().submit(() -> setupDeployment(model))
                            .compose(ignore -> verifyLimit())
                            .compose(ignore -> proxy.getTokenStatsTracker().startSpan(context)
                                    .map(ignored -> handleRequestBody(request)))
                            .otherwise(error -> handleRequestError(model, error));
                })
                .onFailure(this::handleRequestBodyError);

        return Future.succeededFuture();
    }

    private Future<Void> verifyLimit() {
        return proxy.getRateLimiter().limit(context, context.getDeployment())
                .map(rateLimit -> {
                    rateLimit.throwIfError();
                    return null;
                });
    }

    @SneakyThrows
    private Void handleRequestBody(RequestObject request) {
        context.setStreamingRequest(request.isStreaming());
        prepareUpstreamRoute(request);
        sendRequest();

        return null;
    }

    @Override
    protected void processResponse(HttpClientResponse proxyResponse) {
        if (!context.isStreamingRequest()) {
            proxyResponse.body()
                    .compose(body -> handleNonStreamingResponse(proxyResponse, body))
                    .onFailure(this::handleProxyConnectionError);
            return;
        }

        BufferingReadStream responseStream = createResponseStream(proxyResponse,
                () -> new MessagesSseListener(List.of(new CollectMessagesTokenUsageFn(proxy, context))));

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(context.getUpstreamRoute().getAttemptCount()));

        responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignored -> handleResponse(responseStream))
                .onFailure(error -> handleResponseError(error, responseStream));
    }

    private Future<Void> handleNonStreamingResponse(HttpClientResponse proxyResponse, Buffer body) {
        context.setResponseBody(body);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);
        response.setChunked(false);
        response.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(context.getUpstreamRoute().getAttemptCount()));
        return response.end(body)
                .compose(ignore -> collectTokenUsage(context.getResponseBody()))
                .transform(result -> {
                    if (result.failed()) {
                        log.warn("Failed to collect token usage", result.cause());
                    }
                    completeProxyResponse();
                    return Future.<Void>succeededFuture();
                });
    }

    private void handleResponse(BufferingReadStream responseStream) {
        Buffer responseBody = responseStream.getContent();
        context.setResponseBody(responseBody);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        collectTokenUsage(responseBody).onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to collect token usage", result.cause());
            }
            HttpServerResponse response = context.getResponse();
            responseStream.end(response);
            completeProxyResponse();
        });
    }

    private void completeProxyResponse() {
        proxy.getLogStore().save(context);
        Upstream currentUpstream = context.getUpstreamRoute().get();
        log.info("Sent response to client. Deployment: {}. Interface: {}. Upstream: {}. Length: {}. Tokens: {}.",
                context.getDeployment().getName(),
                InterfaceType.ANTHROPIC_MESSAGES.getValue(),
                currentUpstream == null ? "N/A" : currentUpstream.getEndpoint(),
                context.getResponseBody() == null ? 0 : context.getResponseBody().length(),
                context.getTokenUsage() == null ? "N/A" : context.getTokenUsage());

        finalizeRequest();
    }

    private void handleResponseError(Throwable error, BufferingReadStream responseStream) {
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        log.warn("Can't send response to client. Error:", error);
        Deployment deployment = context.getDeployment();
        if (deployment instanceof Model) {
            // make sure we collect token usage in case if client accidentally closed the connection
            responseStream.endStreamFuture()
                    .onFailure(ignore -> context.getProxyRequest().reset()) // drop connection to stop origin response
                    .compose(ignore -> {
                        Buffer responseBody = responseStream.getContent();
                        context.setResponseBody(responseBody);
                        context.setResponseBodyTimestamp(System.currentTimeMillis());
                        return collectTokenUsage(responseBody);
                    })
                    .onSuccess(ignored -> proxy.getLogStore().save(context))
                    .onComplete(ignored -> finalizeRequest());
        } else {
            // drop connection to stop application responding
            context.getProxyRequest().reset();
        }
    }

    @Override
    protected TokenUsage parseTokenUsage(Buffer responseBody) {
        if (context.isStreamingRequest()) {
            // Populated event-by-event by CollectMessagesTokenUsageFn during streaming.
            return context.getTokenUsage();
        }
        return MessagesTokenUsageParser.parse(responseBody);
    }
}
