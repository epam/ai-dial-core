package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJacksonCodec;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;


@Slf4j
public class ResourceTopic {

    private static final String COMPLEX_FILES_SEGMENT = "files";

    private final Map<String, Set<Subscription>> urlToSubscriptions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ResourceEvent>> globalSubscribers = new CopyOnWriteArrayList<>();
    private final RTopic topic;

    public ResourceTopic(RedissonClient redis, String topicKey) {
        this(redis, topicKey, new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL));
    }

    public ResourceTopic(RedissonClient redis, String topicKey, ObjectMapper mapper) {
        this.topic = redis.getTopic(topicKey, new TypedJsonJacksonCodec(ResourceEvent.class, mapper));
        topic.addListener(ResourceEvent.class, (channel, event) -> handle(event));
    }

    public void publish(ResourceEvent event) {
        topic.publish(event);
    }

    public Subscription subscribe(Collection<ResourceDescriptor> resources, Consumer<ResourceEvent> subscriber) {
        Subscription subscription = new Subscription(resources, subscriber);

        for (ResourceDescriptor resource : resources) {
            String url = resource.getUrl();
            urlToSubscriptions.compute(url, (key, subs) -> {
                if (subs == null) {
                    subs = ConcurrentHashMap.newKeySet();
                }

                subs.add(subscription);
                return subs;
            });
        }

        return subscription;
    }

    public Subscription subscribeAll(Consumer<ResourceEvent> subscriber) {
        Subscription subscription = new Subscription(List.of(), subscriber);
        globalSubscribers.add(subscriber);
        return subscription;
    }

    private void unsubscribe(Subscription subscription) {
        for (ResourceDescriptor resource : subscription.resources) {
            String url = resource.getUrl();
            urlToSubscriptions.computeIfPresent(url, (key, subs) -> {
                subs.remove(subscription);
                return subs.isEmpty() ? null : subs;
            });
        }
        globalSubscribers.remove(subscription.subscriber);
    }

    private void handle(ResourceEvent event) {
        String url = event.getUrl();
        notifySubscribers(urlToSubscriptions.getOrDefault(url, Set.of()), event);

        String resourceUrl = complexResourceUrl(url);
        if (resourceUrl != null) {
            notifySubscribers(urlToSubscriptions.getOrDefault(resourceUrl, Set.of()), event);
        }
        for (Consumer<ResourceEvent> subscriber : globalSubscribers) {
            try {
                subscriber.accept(event);
            } catch (Throwable e) {
                log.warn("Can't notify global subscriber", e);
            }
        }
    }

    private static void notifySubscribers(Set<Subscription> subscriptions, ResourceEvent event) {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.subscriber.accept(event);
            } catch (Throwable e) {
                log.warn("Can't notify subscriber", e);
            }
        }
    }

    /**
     * Returns the URL of the complex resource that owns the file the given event URL points to
     * ({@code {group}/{bucket}/{folders}/{name}/files/{path}}), or {@code null} if it is not such a file URL.
     * A complex resource and its folders are never named "files".
     */
    private static String complexResourceUrl(String url) {
        if (url == null) {
            return null;
        }
        String[] segments = url.split("/");
        // group, bucket, at least a resource name, the files segment and a file name
        if (segments.length < 5 || !isComplexGroup(segments[0])) {
            return null;
        }
        for (int i = 3; i < segments.length - 1; i++) {
            if (COMPLEX_FILES_SEGMENT.equals(segments[i])) {
                return String.join("/", Arrays.copyOfRange(segments, 0, i));
            }
        }
        return null;
    }

    private static boolean isComplexGroup(String group) {
        try {
            return ResourceTypes.of(group).isComplex();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Value
    public class Subscription implements AutoCloseable {

        AtomicBoolean active = new AtomicBoolean(true);
        Collection<ResourceDescriptor> resources;
        Consumer<ResourceEvent> subscriber;

        @Override
        public void close() {
            if (active.getAndSet(false)) {
                unsubscribe(this);
            }
        }
    }
}