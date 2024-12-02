package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TieredBalancerTest {

    @Mock
    private Vertx vertx;

    @Mock
    private Random generator;

    @Test
    void testTierPriority() {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint1", null, null, 1, 0),
                new Upstream("endpoint2", null, null, 9, 1)
        );
        TieredBalancer balancer = new TieredBalancer("model1", upstreams, generator);

        // verify all requests go to the highest tier
        for (int j = 0; j < 50; j++) {
            Upstream upstream = balancer.next(Set.of());
            assertNotNull(upstream);
            assertEquals("endpoint1", upstream.getEndpoint());
        }
    }

    @Test
    void testFail() throws InterruptedException {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint1", null, null, 1, 0),
                new Upstream("endpoint2", null, null, 9, 1)
        );
        TieredBalancer balancer = new TieredBalancer("model1", upstreams, generator);

        Upstream upstream = balancer.next(Set.of());
        assertNotNull(upstream);
        assertEquals("endpoint1", upstream.getEndpoint());

        // fail tier 2 endpoint
        balancer.fail(upstream, HttpStatus.TOO_MANY_REQUESTS, 1);

        // verify only tier 1 available
        for (int i = 0; i < 10; i++) {
            upstream = balancer.next(Set.of());
            assertNotNull(upstream);
            assertEquals("endpoint2", upstream.getEndpoint());
        }

        // wait once tier 2 become available again
        Thread.sleep(2000);

        upstream = balancer.next(Set.of());
        assertNotNull(upstream);
        assertEquals("endpoint1", upstream.getEndpoint());
    }

    @Test
    void test5xxErrorsHandling() {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint0", null, null, 1, 0),
                new Upstream("endpoint1", null, null, 1, 1)
        );
        TieredBalancer balancer = new TieredBalancer("model1", upstreams, generator);

        Set<Upstream> used = new HashSet<>();
        // report upstream failure 4 times
        for (int i = 0; i < 4; i++) {
            Upstream upstream = balancer.next(used);
            assertNotNull(upstream);
            assertEquals("endpoint" + i % 2, upstream.getEndpoint());

            balancer.fail(upstream, HttpStatus.SERVICE_UNAVAILABLE, -1);
        }
        // there are no more unused upstreams left
        Upstream upstream = balancer.next(used);
        assertNull(upstream);
    }

    @Test
    void testUpstreamFallback() {

        Model model = new Model();
        model.setName("model1");
        List<Upstream> upstreams = Stream.of(1, 2, 3, 4)
                .map(index  -> new Upstream("endpoint" + index, null, null, 1, 1))
                .toList();
        model.setUpstreams(upstreams);
        AtomicInteger counter = new AtomicInteger();
        when(generator.nextInt(5)).thenAnswer(cb -> counter.incrementAndGet());
        Supplier<Random> factory = () -> generator;

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, factory);

        UpstreamRoute route1 = upstreamRouteProvider.get(model);
        assertEquals(upstreams.get(0), route1.get());
        route1.fail(HttpStatus.SERVICE_UNAVAILABLE, -1);
        assertEquals(upstreams.get(1), route1.next());
        route1.fail(HttpStatus.SERVICE_UNAVAILABLE, 5);
        assertEquals(upstreams.get(2), route1.next());
        route1.fail(HttpStatus.TOO_MANY_REQUESTS, -1);
        assertEquals(upstreams.get(3), route1.next());
        route1.fail(HttpStatus.TOO_MANY_REQUESTS, 5);

        UpstreamRoute route2 = upstreamRouteProvider.get(model);
        assertEquals(upstreams.get(0), route2.get());
        route2.fail(HttpStatus.SERVICE_UNAVAILABLE, -1);

        assertEquals(upstreams.get(1), route2.next());
        route2.fail(HttpStatus.SERVICE_UNAVAILABLE, 5);

        assertEquals(upstreams.get(2), route2.next());
        route2.fail(HttpStatus.TOO_MANY_REQUESTS, -1);

        assertEquals(upstreams.get(3), route2.next());
        route2.fail(HttpStatus.TOO_MANY_REQUESTS, 5);

        assertNull(route2.next());
    }
}
