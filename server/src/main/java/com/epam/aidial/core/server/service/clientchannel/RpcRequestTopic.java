package com.epam.aidial.core.server.service.clientchannel;

import com.epam.aidial.core.server.data.clientchannel.topic.PubSubRequest;
import com.epam.aidial.core.server.jsonrpc.domain.RpcRequest;
import com.epam.aidial.core.storage.service.TimerService;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJacksonCodec;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public class RpcRequestTopic {

    private final RTopic topic;
    private final Map<String, Set<RpcRequestSubscription>> subscriptions = new ConcurrentHashMap<>();

    public RpcRequestTopic(RedissonClient redis, String topicKey, TimerService timerService, long watchdogPeriod) {
        this.topic = redis.getTopic(topicKey, new TypedJsonJacksonCodec(PubSubRequest.class));
        new RedisTopicWatchdog<>(topic, PubSubRequest.class, "RpcRequestTopic", timerService, watchdogPeriod,
                (channelId, nonce) -> {
                    RpcRequest canary = new RpcRequest();
                    canary.setId(new TextNode(nonce));
                    return new PubSubRequest(channelId, canary);
                },
                PubSubRequest::getChannelId,
                request -> request.getRequest().getId().asText(),
                this::handle);
    }

    public void publish(PubSubRequest request) {
        log.debug("Publish RPC request {} to Redis topic RPC Requests. Channel ID {}",
                request.getRequest().getId().asText(), request.getChannelId());
        topic.publish(request);
    }

    public RpcRequestSubscription subscribe(String channelId, Consumer<RpcRequest> subscriber) {
        log.debug("Subscribe new subscriber on Redis topic RPC Requests. Client channel {}", channelId);
        RpcRequestSubscription subscription = new RpcRequestSubscription(channelId, subscriber);
        subscriptions.compute(channelId, (k, subs) -> {
            if (subs == null) {
                subs = ConcurrentHashMap.newKeySet();
            }
            subs.add(subscription);
            return subs;
        });
        return subscription;
    }

    private void handle(PubSubRequest pubSubRequest) {
        Set<RpcRequestSubscription> subs = subscriptions.getOrDefault(pubSubRequest.getChannelId(), Set.of());
        for (RpcRequestSubscription subscription : subs) {
            log.debug("Received RPC request ID {} to Redis topic RPC Requests. Channel ID {}",
                    pubSubRequest.getRequest().getId(), pubSubRequest.getChannelId());
            subscription.subscriber.accept(pubSubRequest.getRequest());
        }
    }

    private void unsubscribe(RpcRequestSubscription subscription) {
        log.debug("Unsubscribe to Redis topic RPC Requests. Channel ID {}", subscription.channelId);
        subscriptions.computeIfPresent(subscription.channelId, (k, subs) -> {
            subs.remove(subscription);
            return subs.isEmpty() ? null : subs;
        });
    }

    public class RpcRequestSubscription implements AutoCloseable {

        private final AtomicBoolean active = new AtomicBoolean(true);
        private final String channelId;
        private final Consumer<RpcRequest> subscriber;

        public RpcRequestSubscription(String channelId, Consumer<RpcRequest> subscriber) {
            this.subscriber = subscriber;
            this.channelId = channelId;
        }

        @Override
        public void close() {
            if (active.getAndSet(false)) {
                unsubscribe(this);
            }
        }
    }
}
