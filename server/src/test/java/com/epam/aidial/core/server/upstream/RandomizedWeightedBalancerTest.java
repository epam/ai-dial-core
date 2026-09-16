package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Upstream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RandomizedWeightedBalancerTest {

    @Mock
    private Random generator;

    @Test
    void testWeightedLoadBalancer() {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint1", null, null, null, null, 1, 0, null, null, null),
                new Upstream("endpoint2", null, null, null, null, 2, 0, null, null, null),
                new Upstream("endpoint3", null, null, null, null, 3, 0, null, null, null),
                new Upstream("endpoint4", null, null, null, null, 4, 0, null, null, null)
        );

        RandomizedWeightedBalancer balancer = new RandomizedWeightedBalancer("model1", upstreams, generator);

        when(generator.nextInt(10)).thenReturn(0);

        Upstream upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals(upstreams.get(0), upstream);

        when(generator.nextInt(10)).thenReturn(2);

        upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals(upstreams.get(1), upstream);

        when(generator.nextInt(10)).thenReturn(5);

        upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals(upstreams.get(2), upstream);

        when(generator.nextInt(10)).thenReturn(9);

        upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals(upstreams.get(3), upstream);

    }

    @Test
    void testZeroWeightLoadBalancer() {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint1", null, null, null, null, 0, 1, null, null, null),
                new Upstream("endpoint2", null, null, null, null, -9, 1, null, null, null)
        );
        RandomizedWeightedBalancer balancer = new RandomizedWeightedBalancer("model1", upstreams, generator);

        for (int i = 0; i < 10; i++) {
            Upstream upstream = balancer.next();
            assertNull(upstream);
        }
    }

}
