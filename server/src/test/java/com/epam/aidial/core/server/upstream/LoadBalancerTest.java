package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.util.HttpStatus;
import org.apache.commons.lang3.mutable.MutableInt;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class LoadBalancerTest {

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
            UpstreamState upstream = balancer.next();
            assertNotNull(upstream);
            assertEquals("endpoint1", upstream.getUpstream().getEndpoint());
        }
    }

    @Test
    void testLoadBalancerFailure() throws InterruptedException {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint1", null, null, 1, 0),
                new Upstream("endpoint2", null, null, 9, 1)
        );
        TieredBalancer balancer = new TieredBalancer("model1", upstreams);

        UpstreamState upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals("endpoint1", upstream.getUpstream().getEndpoint());

        // fail tier 2 endpoint
        upstream.failed(HttpStatus.TOO_MANY_REQUESTS, 1);

        // verify only tier 1 available
        for (int i = 0; i < 10; i++) {
            upstream = balancer.next();
            assertNotNull(upstream);
            assertEquals("endpoint2", upstream.getUpstream().getEndpoint());
        }

        // wait once tier 2 become available again
        Thread.sleep(2000);

        upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals("endpoint1", upstream.getUpstream().getEndpoint());
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
                new Upstream("endpoint1", null, null, 1, 0),
                new Upstream("endpoint2", null, null, 1, 1)
        );
        TieredBalancer balancer = new TieredBalancer("model1", upstreams);

        // report upstream failure 3 times
        for (int i = 0; i < 3; i++) {
            UpstreamState upstream = balancer.next();
            assertNotNull(upstream);
            assertEquals("endpoint1", upstream.getUpstream().getEndpoint());

            upstream.failed(HttpStatus.SERVICE_UNAVAILABLE, 1);
        }

        UpstreamState upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals("endpoint2", upstream.getUpstream().getEndpoint());
    }

    @Test
    void testUpstreamRefresh() {
        Config config = new Config();
        Map<String, Model> models = new HashMap<>();
        config.setModels(models);

        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, 1, 1),
                new Upstream("endpoint2", null, null, 1, 1)
        ));

        models.put("model1", model);
        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider();
        upstreamRouteProvider.onUpdate(config);

        UpstreamRoute route = upstreamRouteProvider.get(new DeploymentUpstreamProvider(model));
        Upstream upstream;

        // fail 2 upstreams
        for (int i = 0; i < 2; i++) {
            upstream = route.get();
            assertNotNull(upstream);
            route.fail(HttpStatus.TOO_MANY_REQUESTS, 100);
            route.next();
        }

        upstream = route.get();
        assertNull(upstream);

        Model model1 = new Model();
        model1.setName("model1");
        model1.setUpstreams(List.of(
                new Upstream("endpoint2", null, null, 1, 1),
                new Upstream("endpoint1", null, null, 1, 1)
        ));

        models.put("model1", model1);
        upstreamRouteProvider.onUpdate(config);

        // upstreams remains the same, state must not be invalidated
        route = upstreamRouteProvider.get(new DeploymentUpstreamProvider(model1));

        upstream = route.get();
        assertNull(upstream);

        Model model2 = new Model();
        model2.setName("model1");
        model2.setUpstreams(List.of(
                new Upstream("endpoint2", null, null, 5, 1),
                new Upstream("endpoint1", null, null, 1, 1)
        ));

        models.put("model1", model2);
        upstreamRouteProvider.onUpdate(config);

        // upstreams updated, current state must be evicted
        route = upstreamRouteProvider.get(new DeploymentUpstreamProvider(model2));

        upstream = route.get();
        assertNotNull(upstream);
        assertEquals("endpoint2", upstream.getEndpoint());
    }
}
