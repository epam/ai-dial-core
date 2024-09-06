package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.util.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UpstreamRouteTest {

    private final UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider();

    @Test
    void testUpstreamRouteWithRetry() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, 1, 1),
                new Upstream("endpoint2", null, null, 1, 1),
                new Upstream("endpoint3", null, null, 1, 1),
                new Upstream("endpoint4", null, null, 1, 1)
        ));

        UpstreamRoute route = upstreamRouteProvider.get(new DeploymentUpstreamProvider(model));

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(1, route.used());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        route.next();

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(2, route.used());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        route.next();

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(3, route.used());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        route.next();

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(4, route.used());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        route.next();

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(5, route.used());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        route.next();

        // verify route reach max attempts
        assertFalse(route.available());
        assertNull(route.get());
        assertEquals(5, route.used());
    }

    @Test
    void testUpstreamRouteWithRetry2() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, 1, 1),
                new Upstream("endpoint2", null, null, 1, 1)
        ));

        UpstreamRoute route = upstreamRouteProvider.get(new DeploymentUpstreamProvider(model));

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(1, route.used());

        route.fail(HttpStatus.TOO_MANY_REQUESTS, 30);
        route.next();

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(2, route.used());

        route.fail(HttpStatus.TOO_MANY_REQUESTS, 30);
        route.next();

        assertFalse(route.available());
        assertNull(route.get());
        assertEquals(3, route.used());
    }
}
