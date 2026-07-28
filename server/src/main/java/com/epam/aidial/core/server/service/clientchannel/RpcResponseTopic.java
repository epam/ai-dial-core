package com.epam.aidial.core.server.service.clientchannel;

import com.epam.aidial.core.server.data.clientchannel.topic.PubSubResponse;
import com.epam.aidial.core.server.jsonrpc.domain.RpcResponse;
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
public class RpcResponseTopic {

    private final RTopic topic;
    private final Map<Key, Set<RpcResponseSubscription>> subscriptions = new ConcurrentHashMap<>();

    public RpcResponseTopic(RedissonClient redis, String topicKey) {
        this.topic = redis.getTopic(topicKey, new TypedJsonJacksonCodec(PubSubResponse.class));
        topic.addListener(PubSubResponse.class, (channel, message) -> handle(message));
    }

    public void publish(PubSubResponse response) {
        long receivers = topic.publish(response);
        log.debug("Publish RPC response {} to Redis topic RPC Responses. Channel ID {}. Receivers: {}",
                response.getResponse().getId().asText(), response.getChannelId(), receivers);
    }

    public RpcResponseSubscription subscribe(String channelId, String requestId, Consumer<RpcResponse> subscriber) {
        log.debug("Subscribe new subscriber on Redis topic RPC Responses. Client channel {}", channelId);
        RpcResponseTopic.RpcResponseSubscription subscription = new RpcResponseTopic.RpcResponseSubscription(channelId, requestId, subscriber);
        Key key = new Key(channelId, requestId);
        subscriptions.compute(key, (k, subs) -> {
            if (subs == null) {
                subs = ConcurrentHashMap.newKeySet();
            }
            subs.add(subscription);
            return subs;
        });
        log.debug("Registered local subscription for channel {} request {}. Local subscriber count: {}",
                channelId, requestId, subscriptions.getOrDefault(key, Set.of()).size());
        return subscription;
    }

    private void handle(PubSubResponse pubSubResponse) {
        String channelId = pubSubResponse.getChannelId();
        String requestId = pubSubResponse.getResponse().getId().asText();
        Key key = new Key(channelId, requestId);
        Set<RpcResponseSubscription> subs = subscriptions.getOrDefault(key, Set.of());
        log.debug("Handling published RPC response for channel {} request {}. Local subscriber count: {}",
                channelId, requestId, subs.size());
        for (RpcResponseSubscription subscription : subs) {
            log.debug("Received RPC response ID {} to Redis topic RPC Responses. Channel ID {}",
                    pubSubResponse.getResponse().getId(), pubSubResponse.getChannelId());
            subscription.subscriber.accept(pubSubResponse.getResponse());
        }
    }

    private void unsubscribe(RpcResponseSubscription subscription) {
        log.debug("Unsubscribe to Redis topic RPC Responses. Channel ID {}", subscription.channelId);
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
