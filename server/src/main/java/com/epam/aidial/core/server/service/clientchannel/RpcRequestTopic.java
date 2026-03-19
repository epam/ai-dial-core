package com.epam.aidial.core.server.service.clientchannel;

import com.epam.aidial.core.server.data.clientchannel.topic.PubSubRequest;
import com.epam.aidial.core.server.jsonrpc.domain.RpcRequest;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJacksonCodec;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class RpcRequestTopic {

    private final RTopic topic;
    private final Map<String, Set<RpcRequestSubscription>> subscriptions = new ConcurrentHashMap<>();

    public RpcRequestTopic(RedissonClient redis, String topicKey) {
        this.topic = redis.getTopic(topicKey, new TypedJsonJacksonCodec(PubSubRequest.class));
        topic.addListener(PubSubRequest.class, (channel, message) -> handle(message));
    }

    public void publish(PubSubRequest request) {
        topic.publish(request);
    }

    public RpcRequestSubscription subscribe(String channelId, Consumer<RpcRequest> subscriber) {
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
            subscription.subscriber.accept(pubSubRequest.getRequest());
        }
    }

    private void unsubscribe(RpcRequestSubscription subscription) {
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
