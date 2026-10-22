package com.epam.aidial.core.server.service.clientchannel;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pub/sub must survive Redis moving to a new IP. Redisson 3.27.0 leaked the per-channel subscription lock
 * on such a master change (NPE in PublishSubscribeService.remove), so the subscription was never restored.
 * Fixed in Redisson 3.40.1 (redisson/redisson#6023).
 *
 * <p>The "move" is simulated by two Redis instances on the same port bound to 127.0.0.1 and 127.0.0.2 and a
 * resolver that flips the hostname between them. Needs 127.0.0.2 to be routable on the loopback interface,
 * which is the default on Linux and requires {@code ifconfig lo0 alias 127.0.0.2} on macOS.
 */
class RedissonPubSubFailoverTest {

    private static final int PORT = 16399;
    private static final String OLD_IP = "127.0.0.1";
    private static final String NEW_IP = "127.0.0.2";
    private static final long RECOVERY_TIMEOUT_MS = 30_000;

    private final AtomicReference<String> currentIp = new AtomicReference<>(OLD_IP);
    private RedisServer oldRedis;
    private RedisServer newRedis;
    private RedissonClient client;

    @BeforeEach
    void setUp() throws IOException {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getByName(NEW_IP), 0));
        } catch (IOException e) {
            Assumptions.abort(NEW_IP + " is not available on this machine");
        }

        oldRedis = RedisServer.newRedisServer().port(PORT).bind(OLD_IP).build();
        newRedis = RedisServer.newRedisServer().port(PORT).bind(NEW_IP).build();
        oldRedis.start();
        newRedis.start();

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://redis.failover.test:" + PORT)
                .setDnsMonitoringInterval(1000);
        config.setAddressResolverGroupFactory((datagramChannel, socketChannel, dnsServerAddressStreamProvider) ->
                new SwitchableResolverGroup(currentIp));
        client = Redisson.create(config);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.shutdown(0, 1, TimeUnit.SECONDS);
        }
        newRedis.stop();
        // already stopped by the test itself
        if (oldRedis.isActive()) {
            oldRedis.stop();
        }
    }

    @Test
    void topicKeepsDeliveringMessagesAfterRedisChangesIp() throws Exception {
        RTopic topic = client.getTopic("failover-topic", StringCodec.INSTANCE);
        AtomicInteger received = new AtomicInteger();
        topic.addListener(String.class, (channel, message) -> received.incrementAndGet());

        topic.publish("before-failover");
        assertTrue(awaitDelivery(topic, received, 5_000), "pub/sub does not work before the failover");

        oldRedis.stop();
        currentIp.set(NEW_IP);

        assertTrue(awaitDelivery(topic, received, RECOVERY_TIMEOUT_MS),
                "pub/sub was not restored within " + RECOVERY_TIMEOUT_MS + "ms after Redis changed its IP");
    }

    /**
     * Publishes probes until the listener has seen one more message than before or the timeout expires.
     */
    private static boolean awaitDelivery(RTopic topic, AtomicInteger received, long timeoutMs) throws InterruptedException {
        int expected = received.get() + 1;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                topic.publish("probe");
            } catch (Exception e) {
                // the connection is being switched over, retry
            }
            Thread.sleep(500);
            if (received.get() >= expected) {
                return true;
            }
        }
        return false;
    }

    private static class SwitchableResolverGroup extends AddressResolverGroup<InetSocketAddress> {

        private final AtomicReference<String> ip;

        SwitchableResolverGroup(AtomicReference<String> ip) {
            this.ip = ip;
        }

        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
                @Override
                protected boolean doIsResolved(InetSocketAddress address) {
                    return !address.isUnresolved();
                }

                @Override
                protected void doResolve(InetSocketAddress unresolved, Promise<InetSocketAddress> promise) throws Exception {
                    promise.setSuccess(resolved(unresolved));
                }

                @Override
                protected void doResolveAll(InetSocketAddress unresolved, Promise<List<InetSocketAddress>> promise) throws Exception {
                    promise.setSuccess(List.of(resolved(unresolved)));
                }

                private InetSocketAddress resolved(InetSocketAddress unresolved) throws Exception {
                    byte[] address = InetAddress.getByName(ip.get()).getAddress();
                    return new InetSocketAddress(InetAddress.getByAddress(unresolved.getHostString(), address), unresolved.getPort());
                }
            };
        }
    }
}
