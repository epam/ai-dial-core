package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ResourceEvent;
import com.epam.aidial.core.server.storage.ResourceDescription;
import io.vertx.core.impl.ConcurrentHashSet;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJacksonCodec;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;


@Slf4j
public class ResourceTopic {

    private final Map<String, Set<Subscription>> urlToSubscriptions = new ConcurrentHashMap<>();
    private final RTopic topic;

    public ResourceTopic(RedissonClient redis, String topicKey) {
        this.topic = redis.getTopic(topicKey, new TypedJsonJacksonCodec(ResourceEvent.class));
        topic.addListener(ResourceEvent.class, (channel, event) -> handle(event));
    }

    public void publish(ResourceEvent event) {
        topic.publish(event);
    }

    public Subscription subscribe(Collection<ResourceDescription> resources, Consumer<ResourceEvent> subscriber) {
        Subscription subscription = new Subscription(resources, subscriber);

        for (ResourceDescription resource : resources) {
            String url = resource.getUrl();
            urlToSubscriptions.compute(url, (key, subs) -> {
                if (subs == null) {
                    subs = new ConcurrentHashSet<>();
                }

                subs.add(subscription);
                return subs;
            });
        }

        return subscription;
    }

    private void unsubscribe(Subscription subscription) {
        for (ResourceDescription resource : subscription.resources) {
            String url = resource.getUrl();
            urlToSubscriptions.computeIfPresent(url, (key, subs) -> {
                subs.remove(subscription);
                return subs.isEmpty() ? null : subs;
            });
        }
    }

    private void handle(ResourceEvent event) {
        for (Subscription subscription : urlToSubscriptions.getOrDefault(event.getUrl(), Set.of())) {
            try {
                subscription.subscriber.accept(event);
            } catch (Throwable e) {
                log.warn("Can't notify subscriber", e);
            }
        }
    }

    @Value
    public class Subscription implements AutoCloseable {

        AtomicBoolean active = new AtomicBoolean(true);
        Collection<ResourceDescription> resources;
        Consumer<ResourceEvent> subscriber;

        @Override
        public void close() {
            if (active.getAndSet(false)) {
                unsubscribe(this);
            }
        }
    }
}