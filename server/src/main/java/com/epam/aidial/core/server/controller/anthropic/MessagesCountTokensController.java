package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;

/**
 * {@code POST /anthropic/v1/messages/count_tokens} pass-through. Counts tokens only: it does not
 * generate, so it charges no rate limits (inherits the no-op {@link #verifyLimit()}) and collects
 * no token usage. Always non-streaming.
 */
public class MessagesCountTokensController extends MessagesBaseController {

    public MessagesCountTokensController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected void processResponse(HttpClientResponse proxyResponse) {
        proxyResponse.body()
                .compose(body -> forwardResponse(proxyResponse, body))
                .onFailure(this::handleProxyConnectionError);
    }

    private Future<Void> forwardResponse(HttpClientResponse proxyResponse, Buffer body) {
        context.setResponseBody(body);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        HttpServerResponse response = context.getResponse();
        ProxyUtil.copyResponse(response, proxyResponse);
        response.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(context.getUpstreamRoute().getAttemptCount()));
        // count_tokens must NOT charge limits or collect token usage — just log and finalize.
        return response.end(body)
                .transform(result -> {
                    proxy.getLogStore().save(context);
                    finalizeRequest();
                    return Future.<Void>succeededFuture();
                });
    }
}
