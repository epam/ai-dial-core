package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class UpstreamRouteProviderTest {

    @Test
    public void testGetCustomApp() {
        UpstreamRouteProvider provider = new UpstreamRouteProvider();
        Application application = new Application();
        application.setName("app");
        UpstreamRoute route1 = provider.get(new DeploymentUpstreamProvider(application));
        route1.fail(HttpStatus.TOO_MANY_REQUESTS);
        assertNull(route1.next());
        // make sure new router doesn't have any upstreams for the same application
        UpstreamRoute route2 = provider.get(new DeploymentUpstreamProvider(application));
        assertNull(route2.next());
    }

    @Test
    public void testOnUpdate() {
        Config config = new Config();
        Model model1 = new Model();
        model1.setName("model");
        Upstream upstream1 = new Upstream();
        upstream1.setEndpoint("test");
        upstream1.setTier(0);
        upstream1.setWeight(2);
        model1.setUpstreams(List.of(upstream1));
        config.setModels(Map.of("model", model1));

        UpstreamRouteProvider provider = new UpstreamRouteProvider();
        provider.onUpdate(config);
        UpstreamRoute route1 = provider.get(new DeploymentUpstreamProvider(model1));
        route1.fail(HttpStatus.TOO_MANY_REQUESTS);
        assertNull(route1.next());

        Model model2 = new Model();
        model2.setName("model");
        Upstream upstream2 = new Upstream();
        upstream1.setEndpoint("test2");
        upstream1.setTier(0);
        upstream1.setWeight(1);
        model1.setUpstreams(List.of(upstream2));
        config.setModels(Map.of("model", model2));
        // change upstreams in the model
        provider.onUpdate(config);
        UpstreamRoute route2 = provider.get(new DeploymentUpstreamProvider(model2));
        // the upstream is found
        assertNotNull(route2.next());
    }
}
