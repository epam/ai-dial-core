package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;


class UpstreamBalancerTest {

    @Test
    void testBalancing() {
        Upstream upstream1 = new Upstream();
        upstream1.setEndpoint("upstream1");

        Upstream upstream2 = new Upstream();
        upstream2.setEndpoint("upstream2");

        Upstream upstream3 = new Upstream();
        upstream3.setEndpoint("upstream2");

        Model model = new Model();
        model.setName("chat");
        model.setUpstreams(List.of(upstream1, upstream2, upstream3));

        UpstreamProvider provider = new DeploymentUpstreamProvider(model);
        UpstreamBalancer balancer = new UpstreamBalancer();

        UpstreamRoute route = balancer.balance(provider);

        Assertions.assertEquals(0, route.used());
        Assertions.assertEquals(0, route.retries());

        Assertions.assertEquals(upstream1, route.next());
        Assertions.assertEquals(1, route.used());
        Assertions.assertEquals(0, route.retries());

        Assertions.assertEquals(upstream2, route.next());
        Assertions.assertEquals(2, route.used());
        Assertions.assertEquals(0, route.retries());

        Assertions.assertEquals(upstream3, route.next());
        Assertions.assertEquals(3, route.used());
        Assertions.assertEquals(0, route.retries());

        Assertions.assertNull(route.next());
        Assertions.assertEquals(3, route.used());
        Assertions.assertEquals(0, route.retries());
    }

    @Test
    void testRetries() {
        Upstream upstream1 = new Upstream();
        upstream1.setEndpoint("upstream1");

        Upstream upstream2 = new Upstream();
        upstream2.setEndpoint("upstream2");

        Model model = new Model();
        model.setName("chat");
        model.setUpstreams(List.of(upstream1, upstream2));

        UpstreamProvider provider = new DeploymentUpstreamProvider(model);
        UpstreamBalancer balancer = new UpstreamBalancer();

        UpstreamRoute route = balancer.balance(provider);

        Assertions.assertEquals(0, route.used());
        Assertions.assertEquals(0, route.retries());

        Assertions.assertEquals(upstream1, route.next());
        Assertions.assertEquals(1, route.used());
        Assertions.assertEquals(0, route.retries());

        route.retry();
        route.retry(); // misuse, ignored
        Assertions.assertEquals(0, route.used());
        Assertions.assertEquals(1, route.retries());

        Assertions.assertEquals(upstream1, route.next());
        Assertions.assertEquals(1, route.used());
        Assertions.assertEquals(1, route.retries());

        Assertions.assertEquals(upstream2, route.next());
        Assertions.assertEquals(2, route.used());
        Assertions.assertEquals(1, route.retries());

        route.retry();
        Assertions.assertEquals(1, route.used());
        Assertions.assertEquals(2, route.retries());

        Assertions.assertEquals(upstream2, route.next());
        Assertions.assertEquals(2, route.used());
        Assertions.assertEquals(2, route.retries());

        route.retry();
        Assertions.assertNull(route.next());
        Assertions.assertEquals(1, route.used());
        Assertions.assertEquals(2, route.retries());
    }

}
