package com.epam.aidial.core.token;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.ProxyUtil;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The tracker collects deployment cost stats from multiple DIAL Core instances.
 * The underlined mechanism uses Redis PubSub for communication between Core instances in the cluster.
 * <p>
 * Let's consider a typical workflow of the application A which calls the model M: Core -> A -> Core -> M with two Core instances(Core 1 and Core 2):
 *     <ul>
 *         <li>Core 1 starts a new span S1</li>
 *         <li>Application A calls the model M through the Core 2</li>
 *         <li>Core 2 starts a new span S2 and publishes event to Redis topic</li>
 *         <li>Core 1 receives the start span event of S2 and link S2 to S1. So S1 depends on S2</li>
 *         <li>Core 2 makes a call to the model M and collects stats directly</li>
 *         <li>Core 2 ends the span S2 by publishing the end span event to the topic</li>
 *         <li>Core 1 receives the end span event of S2 with deployment stats. Core 1 marks the span S2 as completed</li>
 *         <li>Core 1 waits for all dependent spans of the span S1 are completed: S1 depends on S2 and S2 is done</li>
 *         <li>Core 1 ends the span S1</li>
 *     </ul>
 * </p>
 * There might be a case when one of Core instances is down for some reasons, e.g. pod is restarted or OOM occurred.
 * A special job is run to finds a such Core instances and mark them as dead by completing all dependent spans associated with the instance.
 * See {@link TokenStatsTracker#cleanupDeadTrackers()} and {@link TokenStatsTracker#ping()} for details.
 */
@Slf4j
public class TokenStatsTracker {


    private final Map<String, TraceContext> traceIdToContext = new HashMap<>();

    /**
     * List of registered trackers in the cluster
     */
    private final Map<String, TrackerData> registeredTrackers = new HashMap<>();

    private final RTopic topic;

    private final Vertx vertx;

    private final LongSupplier clock;

    private final String trackerId;

    public TokenStatsTracker(RedissonClient redis, Vertx vertx, LongSupplier clock, String prefix) {
        this.clock = clock;
        this.vertx = vertx;
        String topicName = "resource:" + BlobStorageUtil.toStoragePath(prefix, "deployment-cost-stats-event");
        this.topic = redis.getTopic(topicName, new StringCodec());
        topic.addListener(String.class, (channel, event) -> onEvent(event));
        this.trackerId = UUID.randomUUID().toString();
        vertx.setPeriodic(0, 60_000, event -> cleanupDeadTrackers());
        vertx.setPeriodic(0, 10_000, event -> ping());
    }

    private synchronized void cleanupDeadTrackers() {
        long currentTime = clock.getAsLong();
        for (Map.Entry<String, TrackerData> entry : registeredTrackers.entrySet()) {
            TrackerData trackerData = entry.getValue();
            String trackerId = entry.getKey();
            if (currentTime - trackerData.lastUpdateTs > 60_000) {
                // complete all promises forcibly
                trackerData.completePromises();
                registeredTrackers.remove(trackerId);
            }
        }
    }

    private void ping() {
        PingEvent event = new PingEvent(this.trackerId);
        publish(Event.PING, event).onFailure(error -> log.error("Can't ping trackers due to error", error));
    }

    private void onEvent(String data) {
        int index = data.indexOf(':');
        String eventName = data.substring(0, index);
        String json = data.substring(index + 1);
        Event event = Event.valueOf(eventName);
        switch (event) {
            case PING -> handlePing(Objects.requireNonNull(ProxyUtil.convertToObject(json, PingEvent.class)));
            case END_SPAN -> handleEndSpan(Objects.requireNonNull(ProxyUtil.convertToObject(json, EndSpanEvent.class)));
            case START_SPAN ->
                    handleStartSpan(Objects.requireNonNull(ProxyUtil.convertToObject(json, StartSpanEvent.class)));
            default -> log.warn("Unsupported event {}", event);
        }
    }

    private synchronized void handlePing(PingEvent event) {
        if (this.trackerId.equals(event.trackerId)) {
            return;
        }
        TrackerData trackerData = getTrackerData(event.trackerId);
        trackerData.lastUpdateTs = clock.getAsLong();
    }

    private TrackerData getTrackerData(String trackerId) {
        return registeredTrackers.computeIfAbsent(trackerId, key -> new TrackerData(clock.getAsLong()));
    }

    /**
     * The handler receives the event about completion of dependent span from another Core instance.
     * <p>
     * Let's consider an example span 1 calls span 2. Span 1 should wait for completion of span 2.
     * So span 2 sends an event to span 1.
     * </p>
     *
     * @param event end span event with deployment stats
     */
    private synchronized void handleEndSpan(EndSpanEvent event) {
        TraceContext traceContext = traceIdToContext.get(event.traceId);
        if (traceContext == null) {
            return;
        }
        Promise<TokenUsage> promise = traceContext.updateSpan(event);
        if (promise != null && !trackerId.equals(event.trackerId)) {
            TrackerData trackerData = getTrackerData(event.trackerId);
            trackerData.removePromise(promise);
        }
    }

    /**
     * The handler receives the event about start process of dependent span from another Core instance.
     * <p>
     * Let's consider an example span 1 depends span 2. Span 1 should be aware that span 2 is started and to be registered as dependent span of span 1.
     * So span 1 will not be completed until span 2.
     * </p>
     *
     * @param event start span event
     */
    private synchronized void handleStartSpan(StartSpanEvent event) {
        TraceContext traceContext = traceIdToContext.get(event.traceId);
        if (traceContext == null) {
            return;
        }
        Promise<TokenUsage> promise = traceContext.updateSpan(event);
        if (promise != null && !trackerId.equals(event.trackerId)) {
            TrackerData trackerData = getTrackerData(event.trackerId);
            trackerData.addPromise(promise);
        }
    }

    private <T> Future<Void> publish(Event event, T payload) {
        String data = event.name() + ":" + ProxyUtil.convertToString(payload);
        return vertx.executeBlocking(() -> {
            topic.publish(data);
            return null;
        }, false);
    }

    /**
     * Starts a new span for the given context.
     *
     * @return the future of check-in span.
     */
    public synchronized Future<Void> startSpan(ProxyContext context) {
        TraceContext traceContext = traceIdToContext.computeIfAbsent(context.getTraceId(), k -> new TraceContext());
        return traceContext.addSpan(context);
    }

    /**
     * Returns deployment cost stats for the given context.
     */
    public synchronized Future<TokenUsage> getTokenStats(ProxyContext context) {
        TraceContext traceContext = traceIdToContext.get(context.getTraceId());
        if (traceContext == null) {
            return Future.succeededFuture();
        }
        return traceContext.getStats(context);
    }

    /**
     * End the current span for the given context.
     *
     * @return the future of check-out span.
     */
    public synchronized Future<Void> endSpan(ProxyContext context) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        if (apiKeyData.getPerRequestKey() == null) {
            traceIdToContext.remove(context.getTraceId());
        } else {
            TraceContext traceContext = traceIdToContext.get(context.getTraceId());
            if (traceContext != null) {
                return traceContext.endSpan(context);
            }
        }
        return Future.succeededFuture();
    }

    @VisibleForTesting
    Map<String, TraceContext> getTraces() {
        return traceIdToContext;
    }

    @VisibleForTesting
    Map<String, TrackerData> getRegisteredTrackers() {
        return registeredTrackers;
    }

    private class TraceContext {
        Map<String, TokenStats> spans = new HashMap<>();

        Future<Void> addSpan(ProxyContext context) {
            String spanId = context.getSpanId();
            String parentSpanId = context.getParentSpanId();
            TokenStats tokenStats = new TokenStats(new TokenUsage(), new HashMap<>());
            spans.put(spanId, tokenStats);
            if (parentSpanId != null) {
                StartSpanEvent event = new StartSpanEvent(trackerId, context.getTraceId(), parentSpanId, context.getSpanId());
                return publish(Event.START_SPAN, event);
            }
            return Future.succeededFuture();
        }

        Promise<TokenUsage> updateSpan(StartSpanEvent event) {
            TokenStats tokenStats = spans.get(event.parentSpanId);
            if (tokenStats == null) {
                return null;
            }
            Promise<TokenUsage> promise = Promise.promise();
            tokenStats.children.put(event.spanId, promise);
            return promise;
        }

        Promise<TokenUsage> updateSpan(EndSpanEvent event) {
            TokenStats tokenStats = spans.get(event.parentSpanId);
            if (tokenStats == null) {
                return null;
            }
            Promise<TokenUsage> promise = tokenStats.children.get(event.spanId);
            if (promise == null) {
                return null;
            }
            promise.complete(event.tokenUsage);
            return promise;
        }

        Future<Void> endSpan(ProxyContext context) {
            String spanId = context.getSpanId();
            spans.remove(spanId);
            if (spans.isEmpty()) {
                TokenStatsTracker.this.traceIdToContext.remove(context.getTraceId());
            }
            String parentSpanId = context.getParentSpanId();
            if (parentSpanId != null) {
                EndSpanEvent event = new EndSpanEvent(trackerId, context.getTraceId(), parentSpanId, context.getSpanId(), context.getTokenUsage());
                return publish(Event.END_SPAN, event);
            }
            return Future.succeededFuture();
        }

        Future<TokenUsage> getStats(ProxyContext context) {
            TokenStats tokenStats = spans.get(context.getSpanId());
            if (tokenStats == null) {
                return Future.succeededFuture();
            }
            TokenUsage tokenUsage = tokenStats.tokenUsage;
            List<Future<TokenUsage>> futures = new ArrayList<>();
            for (Promise<TokenUsage> promise : tokenStats.children.values()) {
                futures.add(promise.future());
            }
            return Future.join(futures).map(result -> {
                for (var child : result.list()) {
                    TokenUsage stats = (TokenUsage) child;
                    tokenUsage.increase(stats);
                }
                return tokenUsage;
            });
        }
    }


    private record TokenStats(TokenUsage tokenUsage, Map<String, Promise<TokenUsage>> children) {
    }

    public record StartSpanEvent(String trackerId, String traceId, String parentSpanId, String spanId) {

    }

    public record EndSpanEvent(String trackerId, String traceId, String parentSpanId, String spanId,
                               TokenUsage tokenUsage) {

    }

    public record PingEvent(String trackerId) {
    }

    public enum Event {
        START_SPAN, END_SPAN, PING
    }

    private static class TrackerData {
        long lastUpdateTs;

        /**
         * List of promises on dependent spans are ended eventually
         */
        final Set<Promise<TokenUsage>> promises = new HashSet<>();

        public TrackerData(long time) {
            this.lastUpdateTs = time;
        }

        void completePromises() {
            for (Promise<TokenUsage> promise : promises) {
                promise.complete(null);
            }
            promises.clear();
        }

        void addPromise(Promise<TokenUsage> promise) {
            promises.add(promise);
        }

        void removePromise(Promise<TokenUsage> promise) {
            promises.remove(promise);
        }
    }
}
