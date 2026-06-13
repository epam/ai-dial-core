package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.jsonrpc.domain.ErrorMessage;
import com.epam.aidial.core.server.jsonrpc.domain.RpcRequest;
import com.epam.aidial.core.server.jsonrpc.domain.RpcResponse;
import com.epam.aidial.core.server.service.HeartbeatService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.clientchannel.ClientChannelService;
import com.epam.aidial.core.server.service.clientchannel.RpcResponseTopic;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
public class ClientChannelController {

    private static final TypeReference<List<RpcRequest>> REQ_BATCH_REF = new TypeReference<>() {
        @Override
        public Type getType() {
            return super.getType();
        }
    };

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final ClientChannelService clientChannelService;
    private final HeartbeatService heartbeatService;

    public ClientChannelController(ProxyContext context) {
        this.context = context;
        Proxy proxy = context.getProxy();
        this.taskExecutor = proxy.getTaskExecutor();
        this.clientChannelService = proxy.getClientChannelService();
        this.heartbeatService = proxy.getHeartbeatService();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/client-channel/subscribe",
            operationId = "subscribeClientChannel",
            contentType = "text/event-stream",
            tags = {"Client channel"},
            parameters = {
                    @ApiParameter(name = Proxy.HEADER_CLIENT_CHANNEL_ID, in = ParameterIn.HEADER,
                            description = OpenApiDescriptions.CLIENT_CHANNEL_ID_RECONNECT)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = String.class, contentTypes = {"text/event-stream"},
                            headers = {
                                    @ApiHeader(name = Proxy.HEADER_CLIENT_CHANNEL_ID, description = "Channel ID for reconnection", required = true)
                            })
            },
            responseProfile = ResponseProfile.OPS_WITH_BAD_REQUEST
    )
    public Future<?> subscribe() {
        HttpServerResponse response = context.getResponse();
        Consumer<RpcRequest> subscriber = this::sendMessage;
        MutableObject<String> channelIdRef = new MutableObject<>();
        taskExecutor.submit(() -> {
            String channelId =  context.getRequest().getHeader(Proxy.HEADER_CLIENT_CHANNEL_ID);
            if (channelId == null) {
                channelId = clientChannelService.createChannel(context);
                response.putHeader(Proxy.HEADER_CLIENT_CHANNEL_ID, channelId);
            }
            channelIdRef.setValue(channelId);
            log.info("Client subscribes on channel {}. User {}. Project {}",
                    channelId, context.getUserId(), context.getProject());
            return clientChannelService.subscribe(channelId, subscriber, context);
        }).onSuccess(subscription -> {

            Runnable heartbeat = this::sendHeartbeat;
            heartbeatService.subscribe(heartbeat);
            setupEventStreamResponse(response);

            response.closeHandler(event -> {
                log.debug("Client closes channel {}. User {}. Project {}",
                        channelIdRef.get(), context.getUserId(), context.getProject());
                heartbeatService.unsubscribe(heartbeat);
                subscription.close();
            });
        }).onFailure(this::handleServiceError);
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/client-channel/report",
            operationId = "reportClientChannel",
            requestBody = RpcResponse.class,
            tags = {"Client channel"},
            parameters = {
                    @ApiParameter(name = Proxy.HEADER_CLIENT_CHANNEL_ID, in = ParameterIn.HEADER, required = true,
                            description = OpenApiDescriptions.CLIENT_CHANNEL_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
            responseProfile = ResponseProfile.OPS_WITH_BAD_REQUEST
    )
    public Future<?> report() {
        String channelId = context.getRequest().getHeader(Proxy.HEADER_CLIENT_CHANNEL_ID);
        if (channelId == null) {
            context.respond(HttpStatus.BAD_REQUEST, "Channel ID is missed");
            return Future.succeededFuture();
        }
        context.getRequest()
                .body()
                .compose(json -> {
                    RpcResponse response = ProxyUtil.convertToObject(json, RpcResponse.class);
                    return taskExecutor.submit(() -> {
                        log.debug("Client reports response {}  to channel {}. User {}. Project {}",
                                response.getId().asText(), channelId, context.getUserId(), context.getProject());
                        clientChannelService.report(channelId, response, context);
                        return null;
                    });
                }).onSuccess(ignored -> context.respond(HttpStatus.OK)).onFailure(this::handleServiceError);
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/client-channel/unsubscribe",
            operationId = "unsubscribeClientChannel",
            tags = {"Client channel"},
            parameters = {
                    @ApiParameter(name = Proxy.HEADER_CLIENT_CHANNEL_ID, in = ParameterIn.HEADER, required = true,
                            description = OpenApiDescriptions.CLIENT_CHANNEL_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
            responseProfile = ResponseProfile.LIMIT_WITH_NOT_FOUND
    )
    public Future<?> unsubscribe() {
        String channelId = context.getRequest().getHeader(Proxy.HEADER_CLIENT_CHANNEL_ID);
        if (channelId == null) {
            context.respond(HttpStatus.BAD_REQUEST, "Channel ID is missed");
            return Future.succeededFuture();
        }
        taskExecutor.submit(() -> clientChannelService.deleteChannel(channelId, context))
                .onSuccess(deleted -> {
                    log.info("Client unsubscribes on channel {}. User {}. Project {}",
                            channelId, context.getUserId(), context.getProject());
                    if (deleted) {
                        context.respond(HttpStatus.OK);
                    } else {
                        context.respond(HttpStatus.NOT_FOUND, "Channel is not found");
                    }
                })
                .onFailure(this::handleServiceError);
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/client-channel/interact",
            operationId = "interactClientChannel",
            requestOneOf = {RpcRequest.class, RpcRequest[].class},
            contentType = "application/json",

            tags = {"Client channel"},
            parameters = {
                    @ApiParameter(name = Proxy.HEADER_CLIENT_CHANNEL_ID, in = ParameterIn.HEADER, required = true,
                            description = OpenApiDescriptions.CLIENT_CHANNEL_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", contentTypes = "text/event-stream", body = String.class)
            },
            responseProfile = ResponseProfile.OPS_WITH_BAD_REQUEST
    )
    public Future<?> interact() {
        HttpServerResponse response = context.getResponse();
        setupEventStreamResponse(response);
        String channelId = context.getRequest().getHeader(Proxy.HEADER_CLIENT_CHANNEL_ID);
        if (channelId == null) {
            handleRpcError(new HttpException(HttpStatus.BAD_REQUEST, "Channel ID is missed"));
            return Future.succeededFuture();
        }
        context.getRequest()
                .body()
                .compose(json -> createResponseSubscriptions(channelId, json))
                .onSuccess(subscriptions -> {
                    Runnable heartbeat = this::sendHeartbeat;
                    heartbeatService.subscribe(heartbeat);
                    response.closeHandler(event -> {
                        log.debug("Deployment closes channel {}. User {}. Project {}",
                                channelId, context.getUserId(), context.getProject());
                        heartbeatService.unsubscribe(heartbeat);
                        for (RpcResponseTopic.RpcResponseSubscription subscription : subscriptions) {
                            subscription.close();
                        }
                    });
                })
                .onFailure(this::handleRpcError);

        return Future.succeededFuture();
    }

    private static void setupEventStreamResponse(HttpServerResponse response) {
        response.setChunked(true)
                .setStatusCode(200)
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                .write(""); // to force writing header
    }

    private Future<List<RpcResponseTopic.RpcResponseSubscription>> createResponseSubscriptions(String channelId, Buffer json) {
        RpcResponseSubscriber subscriber;
        try {
            subscriber = parseRpcRequest(json, channelId);
        } catch (RpcParsingException e) {
            RpcResponse rpcResponse = new RpcResponse(e.errorMessage);
            sendMessage(rpcResponse);
            return Future.succeededFuture(List.of());
        }

        if (subscriber.ready()) {
            subscriber.sendResponse();
            return Future.succeededFuture(List.of());
        } else {
            return taskExecutor.submit(() -> clientChannelService.interact(channelId, subscriber.requestsToBeSent, subscriber, context));
        }
    }

    private RpcResponseSubscriber parseRpcRequest(Buffer buffer, String channelId) {
        JsonNode root;
        try {
            String text = buffer.toString(StandardCharsets.UTF_8);
            root = ProxyUtil.MAPPER.readTree(text);
            String prefix = getRequestIdPrefix();
            log.info("Deployment {} sends request to channel {}. User {}. Project {}", prefix,
                    channelId, context.getUserId(), context.getProject());
            if (root.isArray()) {
                List<RpcRequest> requests = ProxyUtil.MAPPER.treeToValue(root, REQ_BATCH_REF);
                return new RpcResponseSubscriber(requests, true, prefix);
            } else {
                RpcRequest request = ProxyUtil.MAPPER.treeToValue(root, RpcRequest.class);
                return new RpcResponseSubscriber(List.of(request), false, prefix);
            }
        } catch (Exception e) {
            log.warn("Unable to parse RPC request", e);
            ErrorMessage message = new ErrorMessage(-32700, "Parse error", null);
            throw new RpcParsingException(message);
        }
    }

    private String getRequestIdPrefix() {
        String requester = context.getSourceDeployment();
        if (requester == null) {
            if (context.getUserId() != null) {
                return context.getUserId();
            } else {
                return context.getProject();
            }
        }
        return requester;
    }

    /**
     * The subscriber is shared for multiple Redis topic listeners. The listeners handle messages from Redis topic RPC Responses.
     * The subscriber waits for all requests receive responses in a batch request.
     */
    private class RpcResponseSubscriber implements Consumer<RpcResponse> {
        final Map<String, ValueNode> compositeToOriginalRequestId = new HashMap<>();
        final List<RpcResponse> responses = new ArrayList<>();
        final List<RpcRequest> requestsToBeSent = new ArrayList<>();
        final boolean isBatch;

        RpcResponseSubscriber(List<RpcRequest> requests, boolean isBatch, String prefix) {
            for (RpcRequest request : requests) {
                ErrorMessage error = request.validate();
                if (error != null) {
                    RpcResponse rpcResponse = new RpcResponse(error);
                    responses.add(rpcResponse);
                } else {
                    if (!request.getId().isNull()) {
                        String id = prefix + "/" + request.getId().asText();
                        compositeToOriginalRequestId.put(id, request.getId());
                        requestsToBeSent.add(request.copyWith(id));
                    } else {
                        requestsToBeSent.add(request);
                    }
                }
            }
            this.isBatch = isBatch;
        }

        public void accept(RpcResponse response) {
            log.debug("Deployment subscriber {} received response ID {}", context.getSourceDeployment(), response.getId());
            boolean isReadyToSentResponse;
            synchronized (this) {
                ValueNode originalId = compositeToOriginalRequestId.remove(response.getId().asText());
                if (originalId == null) {
                    return;
                }
                responses.add(response.copyWith(originalId));
                isReadyToSentResponse = ready();
            }
            if (isReadyToSentResponse) {
                sendResponse();
            }
        }

        void sendResponse() {
            Object response;
            synchronized (this) {
                if (isBatch) {
                    response = responses;
                } else {
                    response = responses.getFirst();
                }
            }
            sendMessage(response);
            log.debug("Response was sent to deployment subscriber {}", context.getSourceDeployment());
        }

        /**
         * Determines if the subscriber is ready to send a response.
         */
        synchronized boolean ready() {
            return compositeToOriginalRequestId.isEmpty();
        }
    }

    private static class RpcParsingException extends RuntimeException {
        private final ErrorMessage errorMessage;

        public RpcParsingException(ErrorMessage errorMessage) {
            this.errorMessage = errorMessage;
        }
    }

    private void handleServiceError(Throwable error) {
        switch (error) {
            case IllegalArgumentException ignored ->
                    context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
            case PermissionDeniedException httpException ->
                    context.respond(HttpStatus.FORBIDDEN, httpException.getMessage());
            case HttpException httpException -> context.respond(httpException.getStatus(), httpException.getMessage());
            default -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        }
    }

    private void handleRpcError(Throwable error) {
        ErrorMessage errorMessage = new ErrorMessage(-32000, error.getMessage(), null);
        RpcResponse response = new RpcResponse(errorMessage);
        sendMessage(response);
    }

    private void sendMessage(Object message) {
        HttpServerResponse response = context.getResponse();
        try {
            String json = ProxyUtil.convertToString(message);
            response.write("data: " + json + "\n\n");
        } catch (Throwable e) {
            log.warn("Can't send resource event", e);
            response.reset();
        }
    }

    private void sendHeartbeat() {
        HttpServerResponse response = context.getResponse();

        try {
            response.write(": heartbeat\n\n");
        } catch (Throwable e) {
            log.warn("Can't send a heartbeat", e);
            response.reset();
        }
    }
}