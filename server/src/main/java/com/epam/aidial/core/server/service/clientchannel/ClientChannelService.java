package com.epam.aidial.core.server.service.clientchannel;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AuthBucket;
import com.epam.aidial.core.server.data.clientchannel.ClientChannelState;
import com.epam.aidial.core.server.data.clientchannel.PendingMessage;
import com.epam.aidial.core.server.data.clientchannel.topic.PubSubRequest;
import com.epam.aidial.core.server.data.clientchannel.topic.PubSubResponse;
import com.epam.aidial.core.server.jsonrpc.domain.RpcRequest;
import com.epam.aidial.core.server.jsonrpc.domain.RpcResponse;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.util.RedisUtil;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
public class ClientChannelService {

    private final RedissonClient redis;
    private final LockService lockService;
    private final String prefix;
    private final Duration ttl;
    private final RpcRequestTopic rpcRequestTopic;
    private final RpcResponseTopic rpcResponseTopic;

    public ClientChannelService(LockService lockService, RedissonClient redis, String prefix, Duration ttl) {
        this.redis = redis;
        this.lockService = lockService;
        this.prefix = prefix;
        this.ttl = ttl;
        String rpcRequestTopicKey = "topic:" + BlobStorageUtil.toStoragePath(prefix, "CLIENT_CHANNEL/JSON_RPC_REQUESTS");
        String rpcResponseTopicKey = "topic:" + BlobStorageUtil.toStoragePath(prefix, "CLIENT_CHANNEL/JSON_RPC_RESPONSES");
        this.rpcRequestTopic = new RpcRequestTopic(redis, rpcRequestTopicKey);
        this.rpcResponseTopic = new RpcResponseTopic(redis, rpcResponseTopicKey);
    }

    public RpcRequestTopic.RpcRequestSubscription subscribe(String channelId, Consumer<RpcRequest> subscriber, ProxyContext context) {
        updateClientChannelState(channelId, context, clientChannelState -> {
            Iterator<PendingMessage> iterator = clientChannelState.getPendingMessages().values().iterator();
            while (iterator.hasNext()) {
                PendingMessage message = iterator.next();
                PendingMessage.Status status = message.getStatus();
                if (status == PendingMessage.Status.CREATED) {
                    subscriber.accept(message.getRpcRequest());
                    message.setStatus(PendingMessage.Status.SENT);
                    log.debug("Missed RPC request {} is sent to subscriber {}", message.getRpcRequest().getId().asText(), channelId);
                } else if (status == PendingMessage.Status.RECEIVED) {
                    PubSubResponse pubSubResponse = new PubSubResponse(channelId, message.getRpcResponse());
                    rpcResponseTopic.publish(pubSubResponse);
                    iterator.remove();
                    log.debug("Missed RPC response {} is published to Redis topic {}", message.getRpcResponse().getId().asText(), channelId);
                }
            }
        });

        RpcRequestSubscriber rpcRequestSubscriber = new RpcRequestSubscriber(channelId, context, subscriber);
        return rpcRequestTopic.subscribe(channelId, rpcRequestSubscriber);
    }


    private void updateClientChannelState(String channelId, ProxyContext context, Consumer<ClientChannelState> handler) {
        String redisKey = toRedisKey(channelId, context);
        try (var ignored = lockService.lock(redisKey)) {
            RBucket<String> bucket = redis.getBucket(redisKey, StringCodec.INSTANCE);
            String json = bucket.get();
            ClientChannelState clientChannelData = ProxyUtil.convertToObject(json, ClientChannelState.class);
            if (clientChannelData == null) {
                log.debug("Client channel {} is not found", channelId);
                throw new IllegalArgumentException("Client channel is not found for the given ID: " + channelId);
            }

            handler.accept(clientChannelData);

            bucket.set(ProxyUtil.convertToString(clientChannelData));
            bucket.expire(ttl);
        }
    }

    private class RpcRequestSubscriber implements Consumer<RpcRequest> {

        private final String channelId;
        private final ProxyContext context;
        private final Consumer<RpcRequest> subscriber;

        public RpcRequestSubscriber(String channelId, ProxyContext context, Consumer<RpcRequest> subscriber) {
            this.channelId = channelId;
            this.context = context;
            this.subscriber = subscriber;
        }

        @Override
        public void accept(RpcRequest request) {
            log.debug("RpcRequestSubscriber starts receiving request ID {}", request.getId());
            subscriber.accept(request);
            String requestId = request.getId().asText();
            updateClientChannelState(channelId, context, clientChannelState -> {
                PendingMessage message = clientChannelState.getPendingMessages().get(requestId);
                if (message == null) {
                    log.warn("Accept RPC request. Pending message is not found for the given request id: {}", requestId);
                    return;
                }
                message.setStatus(PendingMessage.Status.SENT);
            });
            log.debug("RpcRequestSubscriber finishes receiving request ID {}", request.getId());
        }
    }
    
    public void report(String channelId, RpcResponse response, ProxyContext context) {
        log.debug("Start reporting response ID {} to channel ID {}", response.getId(), channelId);
        PubSubResponse pubSubResponse = new PubSubResponse(channelId, response);
        String requestId = response.getId().asText();
        updateClientChannelState(channelId, context, clientChannelState -> {
            PendingMessage message = clientChannelState.getPendingMessages().get(requestId);
            if (message == null) {
                log.warn("Report RPC response. Pending message is not found for the given request id: {}", requestId);
                return;
            }
            message.setRpcResponse(response);
            message.setStatus(PendingMessage.Status.RECEIVED);
        });
        rpcResponseTopic.publish(pubSubResponse);
        updateClientChannelState(channelId, context, clientChannelState -> clientChannelState.getPendingMessages().remove(requestId));
        log.debug("Finish reporting response ID {} to channel ID {}", response.getId(), channelId);
    }

    public List<RpcResponseTopic.RpcResponseSubscription> interact(String channelId, List<RpcRequest> requestList, Consumer<RpcResponse> subscriber, ProxyContext context) {
        List<String> requestsToBeSubscribed = new ArrayList<>();
        List<RpcResponse> responses = new ArrayList<>();
        List<RpcRequest> requestsToBePublished = new ArrayList<>();
        updateClientChannelState(channelId, context, clientChannelState -> {
            Map<String, PendingMessage> pendingMessageMap = clientChannelState.getPendingMessages();
            for (RpcRequest request : requestList) {
                String requestId = request.getId().asText();
                PendingMessage message = pendingMessageMap.get(requestId);
                if (message != null) {
                    if (message.getStatus() == PendingMessage.Status.RECEIVED) {
                        log.debug("RPC response {} is already received. Channel ID {}", message.getRpcResponse().getId(), channelId);
                        responses.add(message.getRpcResponse());
                    } else {
                        log.debug("Register request {} . Channel ID {}", request.getId(), channelId);
                        requestsToBeSubscribed.add(requestId);
                    }
                } else {
                    requestsToBePublished.add(request);
                    if (!request.getId().isNull()) {
                        log.debug("Adds new pending message for request {} . Channel ID {}", request.getId(), channelId);
                        PendingMessage pendingMessage = new PendingMessage();
                        pendingMessage.setRpcRequest(request);
                        pendingMessage.setStatus(PendingMessage.Status.CREATED);
                        pendingMessageMap.put(requestId, pendingMessage);
                        log.debug("Register request {} . Channel ID {}", request.getId(), channelId);
                        requestsToBeSubscribed.add(requestId);
                    } else {
                        // notification doesn't expect response
                        log.debug("Notification {} is not registered. Channel ID {}", request.getId(), channelId);
                    }
                }
            }
        });
        for (RpcResponse response : responses) {
            subscriber.accept(response);
        }
        List<RpcResponseTopic.RpcResponseSubscription> subscriptions = new ArrayList<>();
        for (String requestId : requestsToBeSubscribed) {
            RpcResponseTopic.RpcResponseSubscription subscription = rpcResponseTopic.subscribe(channelId, requestId, subscriber);
            subscriptions.add(subscription);
        }
        for (RpcRequest request : requestsToBePublished) {
            PubSubRequest pubSubRequest = new PubSubRequest(channelId, request);
            rpcRequestTopic.publish(pubSubRequest);
        }

        return subscriptions;
    }

    public String createChannel(ProxyContext context) {
        String channelId = generateChannelId();
        String redisKey = toRedisKey(channelId, context);
        String json = ProxyUtil.convertToString(new ClientChannelState());
        RBucket<String> bucket = redis.getBucket(redisKey, StringCodec.INSTANCE);
        if (!bucket.setIfAbsent(json)) {
            throw new IllegalStateException(String.format("Client channel %s already exists in Redis storage", channelId));
        }
        bucket.expire(ttl);
        log.debug("Client channel {} is created with ttl {}", channelId, ttl);
        return channelId;
    }

    public boolean deleteChannel(String channelId, ProxyContext context) {
        log.debug("Delete client channel {}", channelId);
        String redisKey = toRedisKey(channelId, context);
        RBucket<String> bucket = redis.getBucket(redisKey);
        return bucket.delete();
    }

    private String generateChannelId() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }

    private String toRedisKey(String channelId, ProxyContext context) {
        AuthBucket authBucket = BucketBuilder.buildBucket(context);
        ResourceDescriptor resource = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.CLIENT_CHANNEL, authBucket.getUserBucket(), authBucket.getUserBucketLocation(), channelId);
        return RedisUtil.redisKey(resource, prefix);
    }
}
