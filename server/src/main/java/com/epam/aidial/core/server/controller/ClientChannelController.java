package com.epam.aidial.core.server.controller;

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
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

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

    public Future<?> subscribe() {
        HttpServerResponse response = context.getResponse();
        Runnable heartbeat = this::sendHeartbeat;
        Consumer<RpcRequest> subscriber = this::sendMessage;
        taskExecutor.submit(() -> {
            String channelId =  context.getRequest().getHeader(Proxy.HEADER_CLIENT_CHANNEL_ID);
            if (channelId == null) {
                channelId = clientChannelService.createChannel(context);
                response.putHeader(Proxy.HEADER_CLIENT_CHANNEL_ID, channelId);
            }
            heartbeatService.subscribe(heartbeat);
            return clientChannelService.subscribe(channelId, subscriber, context);
        }).onSuccess(subscription -> {

            response.setChunked(true)
                    .setStatusCode(200)
                    .putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                    .write(""); // to force writing header

            response.closeHandler(event -> {
                heartbeatService.unsubscribe(heartbeat);
                subscription.close();
            });
        }).onFailure(this::handleServiceError);
        return Future.succeededFuture();
    }

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
                        clientChannelService.report(channelId, response, context);
                        return null;
                    });
                }).onSuccess(ignored -> context.respond(HttpStatus.OK)).onFailure(this::handleServiceError);
        return Future.succeededFuture();
    }

    public Future<?> unsubscribe() {
        String channelId = context.getRequest().getHeader(Proxy.HEADER_CLIENT_CHANNEL_ID);
        if (channelId == null) {
            context.respond(HttpStatus.BAD_REQUEST, "Channel ID is missed");
            return Future.succeededFuture();
        }
        taskExecutor.submit(() -> clientChannelService.deleteChannel(channelId, context))
                .onSuccess(deleted -> {
                    if (deleted) {
                        context.respond(HttpStatus.OK);
                    } else {
                        context.respond(HttpStatus.NOT_FOUND, "Channel is not found");
                    }
                })
                .onFailure(this::handleServiceError);;
        return Future.succeededFuture();
    }

    public Future<?> interact() {
        HttpServerResponse response = context.getResponse();
        String channelId = context.getRequest().getHeader(Proxy.HEADER_CLIENT_CHANNEL_ID);
        if (channelId == null) {
            context.respond(HttpStatus.BAD_REQUEST, "Channel ID is missed");
            return Future.succeededFuture();
        }
        Runnable heartbeat = this::sendHeartbeat;
        context.getRequest()
                .body()
                .compose(json -> {
                    response.setChunked(true)
                            .setStatusCode(200)
                            .putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                            .write(""); // to force writing header
                    return createResponseSubscriptions(channelId, json, heartbeat);
                }).onSuccess(subscriptions -> response.closeHandler(event -> {
                    heartbeatService.unsubscribe(heartbeat);
                    for (RpcResponseTopic.RpcResponseSubscription subscription : subscriptions) {
                        subscription.close();
                    }
                }))
                .onFailure(this::handleServiceError);

        return Future.succeededFuture();
    }

    private Future<List<RpcResponseTopic.RpcResponseSubscription>> createResponseSubscriptions(String channelId, Buffer json, Runnable heartbeat) {
        RpcResponseSubscriber subscriber;
        try {
            subscriber = parseRpcRequest(json);
        } catch (RpcParsingException e) {
            RpcResponse rpcResponse = new RpcResponse(e.errorMessage);
            sendMessage(rpcResponse);
            return Future.succeededFuture(List.of());
        }

        if (subscriber.ready()) {
            subscriber.sendResponse();
            return Future.succeededFuture(List.of());
        } else {
            return taskExecutor.submit(() -> {
                heartbeatService.subscribe(heartbeat);
                return clientChannelService.interact(channelId, subscriber.validRequests, subscriber, context);
            });
        }
    }

    private RpcResponseSubscriber parseRpcRequest(Buffer buffer) {
        JsonNode root;
        try {
            String text = buffer.toString(StandardCharsets.UTF_8);
            root = ProxyUtil.MAPPER.readTree(text);
            String prefix = getRequestIdPrefix();
            if (root.isArray()) {
                List<RpcRequest> requests = ProxyUtil.MAPPER.treeToValue(root, REQ_BATCH_REF);
                return new RpcResponseSubscriber(requests, true, prefix);
            } else {
                RpcRequest request = ProxyUtil.MAPPER.treeToValue(root, RpcRequest.class);
                return new RpcResponseSubscriber(List.of(request), false, prefix);
            }
        } catch (Exception e) {
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

    private class RpcResponseSubscriber implements Consumer<RpcResponse> {
        final Map<String, ValueNode> compositeToOriginalRequestId = new HashMap<>();
        final List<RpcResponse> responses = new ArrayList<>();
        final List<RpcRequest> validRequests = new ArrayList<>();
        final boolean isBatch;
        private int pendingRequests;

        RpcResponseSubscriber(List<RpcRequest> requests, boolean isBatch, String prefix) {
            this.pendingRequests = requests.size();
            for (RpcRequest request : requests) {
                ErrorMessage error = request.validate();
                if (error != null) {
                    RpcResponse rpcResponse = new RpcResponse(error);
                    responses.add(rpcResponse);
                    pendingRequests--;
                } else {
                    if (request.getId() != NullNode.getInstance()) {
                        String id = prefix + "/" + request.getId().asText();
                        compositeToOriginalRequestId.put(id, request.getId());
                        validRequests.add(request.copyWith(id));
                    } else {
                        validRequests.add(request);
                    }
                }
            }
            this.isBatch = isBatch;
        }

        public void accept(RpcResponse response) {
            synchronized (this) {
                ValueNode originalId = compositeToOriginalRequestId.get(response.getId().asText());
                if (originalId == null) {
                    return;
                }
                responses.add(response.copyWith(originalId));
                if (pendingRequests > 0) {
                    pendingRequests--;
                }
            }
            if (ready()) {
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
        }

        synchronized boolean ready() {
            return pendingRequests == 0;
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
