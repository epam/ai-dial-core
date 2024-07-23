package com.epam.aidial.core.token;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.util.ProxyUtil;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Slf4j
public class TokenStatsTracker {


    private final Map<String, TraceContext> traceIdToContext = new ConcurrentHashMap<>();

    private final Map<String, TrackerData> registeredTrackers = new ConcurrentHashMap<>();

    private final RTopic topic;

    private final Vertx vertx;

    private final LongSupplier clock;

    private final String trackerId;

    public TokenStatsTracker(RedissonClient redis, Vertx vertx, LongSupplier clock) {
        this.clock = clock;
        this.vertx = vertx;
        this.topic = redis.getTopic("deployment-cost-stats-event", new StringCodec());
        topic.addListener(String.class, (channel, event) -> onEvent(event));
        this.trackerId = UUID.randomUUID().toString();
        vertx.setPeriodic(0, 60_000, event -> cleanupDeadTrackers());
        vertx.setPeriodic(0, 10_000, event -> ping());
    }

    private void cleanupDeadTrackers() {
        long currentTime = clock.getAsLong();
        for (Map.Entry<String, TrackerData> entry : registeredTrackers.entrySet()) {
            TrackerData trackerData = entry.getValue();
            String trackerId = entry.getKey();
            if (currentTime - trackerData.lastUpdateTs.get() > 60_000) {
                for (Promise<TokenUsage> promise : trackerData.promises) {
                    promise.complete(null);
                }
                registeredTrackers.remove(trackerId);
            }
        }
    }

    private void ping() {
        PingEvent event = new PingEvent(this.trackerId);
        publish(Event.PING, event);
    }

    private void onEvent(String data) {
        int index = data.indexOf(':');
        String eventName = data.substring(0, index);
        String json = data.substring(index + 1);
        Event event = Event.valueOf(eventName);
        switch (event) {
            case PING -> handlePing(Objects.requireNonNull(ProxyUtil.convertToObject(json, PingEvent.class)));
            case END_SPAN -> handleEndSpan(Objects.requireNonNull(ProxyUtil.convertToObject(json, EndSpanEvent.class)));
            case START_SPAN -> handleStartSpan(Objects.requireNonNull(ProxyUtil.convertToObject(json, StartSpanEvent.class)));
            default -> log.warn("Unsupported event {}", event);
        }
    }

    private void handlePing(PingEvent event) {
        if (this.trackerId.equals(event.trackerId)) {
            return;
        }
        TrackerData trackerData = getTrackerData(event.trackerId);
        trackerData.lastUpdateTs.set(clock.getAsLong());
    }

    private TrackerData getTrackerData(String trackerId) {
        return registeredTrackers.computeIfAbsent(trackerId, key -> new TrackerData(clock.getAsLong()));
    }

    private void handleEndSpan(EndSpanEvent event) {
        TraceContext traceContext = traceIdToContext.get(event.traceId);
        if (traceContext == null) {
            return;
        }
        Promise<TokenUsage> promise = traceContext.updateSpan(event);
        if (promise != null) {
            TrackerData trackerData = getTrackerData(event.trackerId);
            trackerData.promises.remove(promise);
        }
    }

    private void handleStartSpan(StartSpanEvent event) {
        TraceContext traceContext = traceIdToContext.get(event.traceId);
        if (traceContext == null) {
            return;
        }
        Promise<TokenUsage> promise = traceContext.updateSpan(event);
        if (promise != null) {
            TrackerData trackerData = getTrackerData(event.trackerId);
            trackerData.promises.add(promise);
        }
    }

    private <T> Future<Void> publish(Event event, T payload) {
        String data = event.name() + ":" + ProxyUtil.convertToString(payload);
        return vertx.executeBlocking(() -> {
            topic.publish(data);
            return null;
        }, false);
    }

    public Future<Void> startSpan(ProxyContext context) {
        TraceContext traceContext = traceIdToContext.computeIfAbsent(context.getTraceId(), k -> new TraceContext());
        return traceContext.addSpan(context);
    }

    public Future<TokenUsage> getTokenStats(ProxyContext context) {
        TraceContext traceContext = traceIdToContext.get(context.getTraceId());
        if (traceContext == null) {
            return Future.succeededFuture();
        }
        return traceContext.getStats(context);
    }

    public Future<Void> endSpan(ProxyContext context) {
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

    private class TraceContext {
        Map<String, TokenStats> spans = new HashMap<>();

        synchronized Future<Void> addSpan(ProxyContext context) {
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

        synchronized Promise<TokenUsage> updateSpan(StartSpanEvent event) {
            TokenStats tokenStats = spans.get(event.parentSpanId);
            if (tokenStats == null) {
                return null;
            }
            Promise<TokenUsage> promise = Promise.promise();
            tokenStats.children.put(event.spanId, Pair.of(promise, promise.future()));
            return promise;
        }

        synchronized Promise<TokenUsage> updateSpan(EndSpanEvent event) {
            TokenStats tokenStats = spans.get(event.parentSpanId);
            if (tokenStats == null) {
                return null;
            }
            Pair<Promise<TokenUsage>, Future<TokenUsage>> pair = tokenStats.children.get(event.spanId);
            if (pair == null) {
                return null;
            }
            pair.getKey().complete(event.tokenUsage);
            return pair.getKey();
        }

        synchronized Future<Void> endSpan(ProxyContext context) {
            String spanId = context.getSpanId();
            spans.remove(spanId);
            if (spans.isEmpty()) {
                TokenStatsTracker.this.traceIdToContext.remove(context.getTraceId());
            }
            EndSpanEvent event = new EndSpanEvent(trackerId, context.getTraceId(), context.getParentSpanId(), context.getSpanId(), context.getTokenUsage());
            return publish(Event.END_SPAN, event);
        }

        synchronized Future<TokenUsage> getStats(ProxyContext context) {
            TokenStats tokenStats = spans.get(context.getSpanId());
            if (tokenStats == null) {
                return Future.succeededFuture();
            }
            TokenUsage tokenUsage = tokenStats.tokenUsage;
            List<Future<TokenUsage>> futures = new ArrayList<>();
            for (Pair<Promise<TokenUsage>, Future<TokenUsage>> pair : tokenStats.children.values()) {
                futures.add(pair.getValue());
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



    private record TokenStats(TokenUsage tokenUsage, Map<String, Pair<Promise<TokenUsage>, Future<TokenUsage>>> children) {
    }

    public record StartSpanEvent(String trackerId, String traceId, String parentSpanId, String spanId) {

    }

    public record EndSpanEvent(String trackerId, String traceId, String parentSpanId, String spanId, TokenUsage tokenUsage) {

    }

    public record PingEvent(String trackerId) {}

    public enum Event {
        START_SPAN, END_SPAN, PING
    }

    private static class TrackerData {
        final AtomicLong lastUpdateTs;
        final Set<Promise<TokenUsage>> promises = Collections.synchronizedSet(new HashSet<>());

        public TrackerData(long time) {
            this.lastUpdateTs = new AtomicLong(time);
        }
    }
}
