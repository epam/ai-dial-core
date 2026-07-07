package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiOperations;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
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

    @ApiOperations({
            @ApiOperation(
                    method = "POST",
                    path = "/anthropic/v1/messages/count_tokens",
                    operationId = "countAnthropicMessageTokens",
                    requestBody = @ApiSchema(schemaRef = "ProxyRequest"),
                    tags = {"Anthropic"},
                    parameters = {
                            @ApiParameter(name = "anthropic-version", in = ParameterIn.HEADER, required = true,
                                    description = "The Anthropic API version (e.g., 2023-06-01)")
                    },
                    responses = {
                            @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                                    body = @ApiSchema(schemaRef = "ProxyResponse"),
                                    headers = {
                                            @ApiHeader(name = Proxy.HEADER_UPSTREAM_ATTEMPTS, description = "Number of upstream attempts performed before returning the response")
                                    }),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 415),
                            @ApiResponse(code = 500),
                            @ApiResponse(code = 502, description = "Bad Gateway - failed to connect to upstream server", body = @ApiSchema(implementation = ErrorData.class)),
                            @ApiResponse(code = 503)
                    }
            )
    })
    @Override
    public Future<?> handle() {
        return super.handle();
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
                    proxy.getLogStore().save(AnalyticsLogContext.from(context));
                    finalizeRequest();
                    return Future.<Void>succeededFuture();
                });
    }
}
