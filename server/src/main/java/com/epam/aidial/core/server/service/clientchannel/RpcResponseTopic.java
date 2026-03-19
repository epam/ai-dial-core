package com.epam.aidial.core.server.service.clientchannel;

import com.epam.aidial.core.server.data.clientchannel.topic.PubSubResponse;
import com.epam.aidial.core.server.jsonrpc.domain.RpcResponse;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJacksonCodec;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class RpcResponseTopic {

    private final RTopic topic;
    private final Map<Key, Set<RpcResponseSubscription>> subscriptions = new ConcurrentHashMap<>();

    public RpcResponseTopic(RedissonClient redis, String topicKey) {
        this.topic = redis.getTopic(topicKey, new TypedJsonJacksonCodec(PubSubResponse.class));
        topic.addListener(PubSubResponse.class, (channel, message) -> handle(message));
    }

    public void publish(PubSubResponse response) {
        topic.publish(response);
    }

    public RpcResponseSubscription subscribe(String channelId, String requestId, Consumer<RpcResponse> subscriber) {
        RpcResponseTopic.RpcResponseSubscription subscription = new RpcResponseTopic.RpcResponseSubscription(channelId, requestId, subscriber);
        Key key = new Key(channelId, requestId);
        subscriptions.compute(key, (k, subs) -> {
            if (subs == null) {
                subs = ConcurrentHashMap.newKeySet();
            }
            subs.add(subscription);
            return subs;
        });
        return subscription;
    }

    private void handle(PubSubResponse pubSubResponse) {
        String channelId = pubSubResponse.getChannelId();
        String requestId = pubSubResponse.getResponse().getId().asText();
        Key key = new Key(channelId, requestId);
        Set<RpcResponseSubscription> subs = subscriptions.getOrDefault(key, Set.of());
        for (RpcResponseSubscription subscription : subs) {
            subscription.subscriber.accept(pubSubResponse.getResponse());
        }
    }

    private void unsubscribe(RpcResponseSubscription subscription) {
        Key key = new Key(subscription.channelId, subscription.requestId);
        subscriptions.computeIfPresent(key, (k, subs) -> {
            subs.remove(subscription);
            return subs.isEmpty() ? null : subs;
        });
    }

    private record Key(String channelId, String requestId){}

    public class RpcResponseSubscription implements AutoCloseable {

        private final AtomicBoolean active = new AtomicBoolean(true);
        private final String channelId;
        private final String requestId;
        private final Consumer<RpcResponse> subscriber;

        public RpcResponseSubscription(String channelId, String requestId, Consumer<RpcResponse> subscriber) {
            this.channelId = channelId;
            this.requestId = requestId;
            this.subscriber = subscriber;
        }

        @Override
        public void close() {
            if (active.getAndSet(false)) {
                unsubscribe(this);
            }
        }
    }

}
