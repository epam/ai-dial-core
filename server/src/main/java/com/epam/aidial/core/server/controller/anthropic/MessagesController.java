package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.controller.ResponsesController;
import com.epam.aidial.core.server.function.CollectMessagesTokenUsageFn;
import com.epam.aidial.core.server.token.MessagesTokenUsageParser;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

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

    @Override
    protected Future<Void> verifyLimit() {
        return proxy.getRateLimiter().limit(context, context.getDeployment())
                .map(rateLimit -> {
                    rateLimit.throwIfError();
                    return null;
                });
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
        ProxyUtil.copyResponse(response, proxyResponse);
        response.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(context.getUpstreamRoute().getAttemptCount()));
        return collectTokenUsage(body)
                .transform(result -> {
                    if (result.failed()) {
                        log.warn("Failed to collect token usage", result.cause());
                    }
                    completeProxyResponse(() -> response.end(body));
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
            completeProxyResponse(() -> responseStream.end(context.getResponse()));
        });
    }

    private void completeProxyResponse(Runnable endResponse) {
        endResponse.run();
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

    @Override
    protected TokenUsage parseTokenUsage(Buffer responseBody) {
        if (context.isStreamingRequest()) {
            // Populated event-by-event by CollectMessagesTokenUsageFn during streaming.
            return context.getTokenUsage();
        }
        return MessagesTokenUsageParser.parse(responseBody);
    }
}
