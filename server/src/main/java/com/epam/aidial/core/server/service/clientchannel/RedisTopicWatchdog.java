package com.epam.aidial.core.server.service.clientchannel;

import com.epam.aidial.core.storage.service.TimerService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.listener.StatusListener;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Redis can silently drop an idle pub/sub subscription connection (e.g. NAT/LB idle timeout) without
 * either side noticing, leaving the app believing it's still subscribed while Redis has no subscriber
 * at all. This periodically publishes a canary message to the topic and, if it's never delivered back
 * to this listener, forces a resubscribe. Also logs Redisson's own subscribe/unsubscribe events for
 * diagnostics. Shared by {@link RpcRequestTopic} and {@link RpcResponseTopic}.
 */
@Slf4j
class RedisTopicWatchdog<T> {

    private static final String WATCHDOG_CHANNEL_ID = "$watchdog$";

    private final RTopic topic;
    private final Class<T> messageType;
    private final String topicName;
    private final BiFunction<String, String, T> canaryFactory;
    private final Function<T, String> channelIdExtractor;
    private final Function<T, String> nonceExtractor;
    private final Consumer<T> messageHandler;

    private final AtomicReference<String> pendingCanary = new AtomicReference<>();
    private final AtomicBoolean watchdogRunning = new AtomicBoolean(false);
    private int listenerId;

    /**
     * @param topicName          used only to prefix log messages, e.g. "RpcRequestTopic"
     * @param watchdogPeriod     interval, in milliseconds, between canary checks
     * @param canaryFactory      (reservedChannelId, nonce) -&gt; canary message to publish
     * @param channelIdExtractor extracts the channel ID from a received message
     * @param nonceExtractor     extracts the nonce/id from a received message
     * @param messageHandler     real dispatch for non-canary messages
     */
    RedisTopicWatchdog(RTopic topic, Class<T> messageType, String topicName, TimerService timerService,
                       long watchdogPeriod, BiFunction<String, String, T> canaryFactory,
                       Function<T, String> channelIdExtractor, Function<T, String> nonceExtractor,
                       Consumer<T> messageHandler) {
        this.topic = topic;
        this.messageType = messageType;
        this.topicName = topicName;
        this.canaryFactory = canaryFactory;
        this.channelIdExtractor = channelIdExtractor;
        this.nonceExtractor = nonceExtractor;
        this.messageHandler = messageHandler;

        registerListeners();
        timerService.scheduleWithFixedDelay(watchdogPeriod, watchdogPeriod, this::checkWatchdog);
    }

    /**
     * Registers both the message listener and a diagnostic {@link StatusListener}. A plain
     * {@code removeListener(id)} + re-{@code addListener} is not enough to force a real resubscribe:
     * Redisson only sends UNSUBSCRIBE/SUBSCRIBE to Redis when a channel transitions to/from having zero
     * listeners, and the StatusListener registered here keeps that count above zero forever. So recovery
     * must go through {@code removeAllListeners()} (which unsubscribes unconditionally) and then
     * re-register everything from scratch.
     */
    private void registerListeners() {
        this.listenerId = topic.addListener(messageType, (channel, message) -> handle(message));
        topic.addListener(new StatusListener() {
            @Override
            public void onSubscribe(String channel) {
                log.info("{} subscription (re)established. Channel {}", topicName, channel);
            }

            @Override
            public void onUnsubscribe(String channel) {
                log.warn("{} subscription lost. Channel {}", topicName, channel);
            }
        });
    }

    private void handle(T message) {
        if (WATCHDOG_CHANNEL_ID.equals(channelIdExtractor.apply(message))) {
            String receivedNonce = nonceExtractor.apply(message);
            pendingCanary.updateAndGet(nonce -> receivedNonce.equals(nonce) ? null : nonce);
            return;
        }
        messageHandler.accept(message);
    }

    private void checkWatchdog() {
        // guards against overlapping ticks without a monitor: topic.publish blocks on Redis I/O,
        // and a synchronized block here would pin the carrier thread for virtual threads (JEP 444)
        if (!watchdogRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            String missedNonce = pendingCanary.getAndSet(null);
            try {
                if (missedNonce != null) {
                    log.warn("{} watchdog canary {} was not received back. Resubscribing listener {}",
                            topicName, missedNonce, listenerId);
                    topic.removeAllListeners();
                    registerListeners();
                }
            } catch (Exception e) {
                // still fall through to publish a fresh canary below, so the next tick can retry
                // detection instead of silently skipping a cycle because pendingCanary was left empty
                log.warn("{} failed to resubscribe after a missed watchdog canary", topicName, e);
            } finally {
                String nonce = UUID.randomUUID().toString();
                pendingCanary.set(nonce);
                topic.publish(canaryFactory.apply(WATCHDOG_CHANNEL_ID, nonce));
            }
        } finally {
            watchdogRunning.set(false);
        }
    }
}
