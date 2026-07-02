package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

/**
 * {@code POST /anthropic/v1/messages/count_tokens} pass-through. Counts tokens only: it does not
 * generate, so it charges no rate limits and collects no token usage. Always non-streaming.
 */
@Slf4j
public class MessagesCountTokensController extends MessagesBaseController {

    public MessagesCountTokensController(Proxy proxy, ProxyContext context) {
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
                            .compose(ignore -> proxy.getTokenStatsTracker().startSpan(context)
                                    .map(ignored -> handleRequestBody(request)))
                            .otherwise(error -> handleRequestError(model, error));
                })
                .onFailure(this::handleRequestBodyError);

        return Future.succeededFuture();
    }

    @SneakyThrows
    private Void handleRequestBody(RequestObject request) {
        prepareUpstreamRoute(request);
        sendRequest();

        return null;
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
        ProxyUtil.handleChunkedResponse(response, proxyResponse);
        response.setChunked(false);
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
