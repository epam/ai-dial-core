package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Vertx;
import org.apache.commons.lang3.mutable.MutableInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
public class LoadBalancerTest {

    @Mock
    private Vertx vertx;
    
    @Test
    void testWeightedLoadBalancer() {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint1", null, null, 1, 0),
                new Upstream("endpoint2", null, null, 9, 0)
        );
        WeightedRoundRobinBalancer balancer = new WeightedRoundRobinBalancer("model1", upstreams);

        Map<String, MutableInt> usage = new HashMap<>();
        usage.put("endpoint1", new MutableInt(0));
        usage.put("endpoint2", new MutableInt(0));

        for (int i = 0; i < 20; i++) {
            UpstreamState upstream = balancer.next();
            assertNotNull(upstream);
            String endpoint = upstream.getUpstream().getEndpoint();
            usage.get(endpoint).increment();
        }

        assertEquals(2, usage.get("endpoint1").getValue());
        assertEquals(18, usage.get("endpoint2").getValue());

        upstreams = List.of(
                new Upstream("endpoint1", null, null, 1, 0),
                new Upstream("endpoint2", null, null, 1, 0),
                new Upstream("endpoint3", null, null, 1, 0),
                new Upstream("endpoint4", null, null, 1, 0)
        );
        balancer = new WeightedRoundRobinBalancer("model1", upstreams);

        usage = new HashMap<>();
        usage.put("endpoint1", new MutableInt(0));
        usage.put("endpoint2", new MutableInt(0));
        usage.put("endpoint3", new MutableInt(0));
        usage.put("endpoint4", new MutableInt(0));

        for (int i = 0; i < 100; i++) {
            UpstreamState upstream = balancer.next();
            assertNotNull(upstream);
            String endpoint = upstream.getUpstream().getEndpoint();
            usage.get(endpoint).increment();
        }

        assertEquals(25, usage.get("endpoint1").getValue());
        assertEquals(25, usage.get("endpoint2").getValue());
        assertEquals(25, usage.get("endpoint3").getValue());
        assertEquals(25, usage.get("endpoint4").getValue());

        upstreams = List.of(
                new Upstream("endpoint1", null, null, 49, 0),
                new Upstream("endpoint2", null, null, 44, 0),
                new Upstream("endpoint3", null, null, 47, 0),
                new Upstream("endpoint4", null, null, 59, 0)
        );
        balancer = new WeightedRoundRobinBalancer("model1", upstreams);

        usage = new HashMap<>();
        usage.put("endpoint1", new MutableInt(0));
        usage.put("endpoint2", new MutableInt(0));
        usage.put("endpoint3", new MutableInt(0));
        usage.put("endpoint4", new MutableInt(0));

        for (int i = 0; i < 398; i++) {
            UpstreamState upstream = balancer.next();
            assertNotNull(upstream);
            String endpoint = upstream.getUpstream().getEndpoint();
            usage.get(endpoint).increment();
        }

        assertEquals(98, usage.get("endpoint1").getValue());
        assertEquals(88, usage.get("endpoint2").getValue());
        assertEquals(94, usage.get("endpoint3").getValue());
        assertEquals(118, usage.get("endpoint4").getValue());
    }

    @Test
    void testTieredLoadBalancer() {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint1", null, null, 1, 0),
                new Upstream("endpoint2", null, null, 9, 1)
        );
        TieredBalancer balancer = new TieredBalancer("model1", upstreams);

        // verify all requests go to the highest tier
        for (int j = 0; j < 50; j++) {
            Upstream upstream = balancer.next(Set.of());
            assertNotNull(upstream);
            assertEquals("endpoint1", upstream.getEndpoint());
        }
    }

    @Test
    void testLoadBalancerFailure() throws InterruptedException {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint1", null, null, 1, 0),
                new Upstream("endpoint2", null, null, 9, 1)
        );
        TieredBalancer balancer = new TieredBalancer("model1", upstreams);

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
    void testZeroWeightLoadBalancer() {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint1", null, null, 0, 1),
                new Upstream("endpoint2", null, null, -9, 1)
        );
        WeightedRoundRobinBalancer balancer = new WeightedRoundRobinBalancer("model1", upstreams);

        for (int i = 0; i < 10; i++) {
            UpstreamState upstream = balancer.next();
            assertNull(upstream);
        }
    }

    @Test
    void test5xxErrorsHandling() {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint0", null, null, 1, 0),
                new Upstream("endpoint1", null, null, 1, 1)
        );
        TieredBalancer balancer = new TieredBalancer("model1", upstreams);

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

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx);

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
