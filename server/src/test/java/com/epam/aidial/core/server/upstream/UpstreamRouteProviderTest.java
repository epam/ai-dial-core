package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
public class UpstreamRouteProviderTest {

    @Mock
    private Vertx vertx;

    @Test
    public void testGet_UpstreamsNotChanged() {
        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx);
        Application application = new Application();
        application.setName("app");
        UpstreamRoute route1 = provider.get(application);
        route1.fail(HttpStatus.TOO_MANY_REQUESTS);
        assertNull(route1.next());
        // make sure new router doesn't have any upstreams for the same application
        UpstreamRoute route2 = provider.get(application);
        assertNull(route2.next());
    }

    @Test
    public void testGet_UpstreamsChanged() {
        Model model = new Model();
        model.setName("model");
        Upstream upstream1 = new Upstream();
        upstream1.setEndpoint("test");
        upstream1.setTier(0);
        upstream1.setWeight(2);
        model.setUpstreams(List.of(upstream1));

        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx);
        UpstreamRoute route1 = provider.get(model);
        route1.fail(HttpStatus.TOO_MANY_REQUESTS);
        assertNull(route1.next());

        Upstream upstream2 = new Upstream();
        upstream2.setEndpoint("test2");
        upstream2.setTier(0);
        upstream2.setWeight(1);
        model.setUpstreams(List.of(upstream2));
        // change upstreams in the model
        UpstreamRoute route2 = provider.get(model);
        // the upstream is found
        assertTrue(route2.available());
    }
}
