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
                new Upstream("endpoint1", null, null, 1, 0),
                new Upstream("endpoint2", null, null, 2, 0),
                new Upstream("endpoint3", null, null, 3, 0),
                new Upstream("endpoint4", null, null, 4, 0)
        );

        RandomizedWeightedBalancer balancer = new RandomizedWeightedBalancer("model1", upstreams, generator);

        when(generator.nextInt(11)).thenReturn(0);

        Upstream upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals(upstreams.get(0), upstream);

        when(generator.nextInt(11)).thenReturn(2);

        upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals(upstreams.get(1), upstream);

        when(generator.nextInt(11)).thenReturn(6);

        upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals(upstreams.get(2), upstream);

        when(generator.nextInt(11)).thenReturn(10);

        upstream = balancer.next();
        assertNotNull(upstream);
        assertEquals(upstreams.get(3), upstream);

    }

    @Test
    void testZeroWeightLoadBalancer() {
        List<Upstream> upstreams = List.of(
                new Upstream("endpoint1", null, null, 0, 1),
                new Upstream("endpoint2", null, null, -9, 1)
        );
        RandomizedWeightedBalancer balancer = new RandomizedWeightedBalancer("model1", upstreams, generator);

        for (int i = 0; i < 10; i++) {
            Upstream upstream = balancer.next();
            assertNull(upstream);
        }
    }

}
