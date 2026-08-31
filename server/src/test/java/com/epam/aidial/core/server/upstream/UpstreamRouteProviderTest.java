package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.UpstreamInterface;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.data.cache.CachedUpstreamEntry;
import com.epam.aidial.core.server.service.UpstreamCacheService;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UpstreamRouteProviderTest {

    @Mock
    private Vertx vertx;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private UpstreamCacheService upstreamCacheService;

    @Mock
    private Random generator;

    @Test
    public void testGet_UpstreamsNotChanged() {
        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        Application application = new Application();
        application.setName("app");
        UpstreamRoute route1 = provider.get(application, null);
        route1.next();
        route1.fail(HttpStatus.TOO_MANY_REQUESTS);
        assertThrows(HttpException.class, route1::next);
        // make sure new router doesn't have any upstreams for the same application
        UpstreamRoute route2 = provider.get(application, null);
        assertNotNull(route2.next());
        assertThrows(HttpException.class, route2::next);
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

        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        CacheBreakpointContext cacheBreakpointContext = new CacheBreakpointContext(List.of(), Map.of(), CachePolicy.AVAILABILITY_PRIORITY);
        UpstreamRoute route1 = provider.get(model, cacheBreakpointContext);
        route1.next();
        route1.fail(HttpStatus.TOO_MANY_REQUESTS);
        assertThrows(HttpException.class, route1::next);

        Upstream upstream2 = new Upstream();
        upstream2.setEndpoint("test2");
        upstream2.setTier(0);
        upstream2.setWeight(1);
        model.setUpstreams(List.of(upstream2));
        // change upstreams in the model
        UpstreamRoute route2 = provider.get(model, cacheBreakpointContext);
        route2.next();
        // the upstream is found
        assertTrue(route2.available());
    }

    @Test
    public void testGet_CachedUpstreamPresent() {
        Model model = new Model();
        model.setName("model");
        Upstream upstream1 = new Upstream();
        upstream1.setEndpoint("upstream1");
        upstream1.setTier(0);
        upstream1.setWeight(2);
        Upstream upstream2 = new Upstream();
        upstream2.setEndpoint("upstream2");
        upstream2.setTier(1);
        upstream2.setWeight(2);
        model.setUpstreams(List.of(upstream1, upstream2));

        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        CacheBreakpointContext cacheBreakpointContext = new CacheBreakpointContext(List.of(), Map.of(), CachePolicy.AVAILABILITY_PRIORITY);
        CachedUpstreamEntry entry = new CachedUpstreamEntry("upstream2", null, "prefix", null);
        when(upstreamCacheService.getCacheEntry(eq(cacheBreakpointContext), eq(model))).thenReturn(entry);

        UpstreamRoute route1 = provider.get(model, cacheBreakpointContext);
        Upstream result = route1.next();

        assertEquals(upstream2, result);
    }

    @Test
    public void testGet_CachedUpstreamMissed() {
        Model model = new Model();
        model.setName("model");
        Upstream upstream1 = new Upstream();
        upstream1.setEndpoint("upstream1");
        upstream1.setTier(0);
        upstream1.setWeight(2);
        Upstream upstream2 = new Upstream();
        upstream2.setEndpoint("upstream2");
        upstream2.setTier(1);
        upstream2.setWeight(2);
        model.setUpstreams(List.of(upstream1, upstream2));

        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        CacheBreakpointContext cacheBreakpointContext = new CacheBreakpointContext(List.of(), Map.of(), CachePolicy.AVAILABILITY_PRIORITY);
        CachedUpstreamEntry entry = new CachedUpstreamEntry("test", null, "prefix", null);
        when(upstreamCacheService.getCacheEntry(eq(cacheBreakpointContext), eq(model))).thenReturn(entry);

        UpstreamRoute route1 = provider.get(model, cacheBreakpointContext);
        Upstream result = route1.next();

        assertEquals(upstream1, result);
    }

    @Test
    public void testGet_UpstreamId_Match() {
        Model model = new Model();
        model.setName("model");
        Upstream upstream1 = new Upstream();
        upstream1.setId("alpha");
        upstream1.setEndpoint("ep1");
        Upstream upstream2 = new Upstream();
        upstream2.setId("beta");
        upstream2.setEndpoint("ep2");
        model.setUpstreams(List.of(upstream1, upstream2));

        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        UpstreamRoute route = provider.get(model, null, "beta");
        Upstream result = route.next();

        assertEquals(upstream2, result);
        assertThrows(HttpException.class, route::next);
    }

    @Test
    public void testGet_UpstreamId_Unknown() {
        Model model = new Model();
        model.setName("model");
        Upstream upstream1 = new Upstream();
        upstream1.setId("alpha");
        upstream1.setEndpoint("ep1");
        model.setUpstreams(List.of(upstream1));

        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        HttpException ex = assertThrows(HttpException.class, () -> provider.get(model, null, "missing"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("Unknown upstream id missing", ex.getMessage());
    }

    @Test
    public void testGet_UpstreamId_MatchesInterfacesConfiguredUpstreamById() {
        // an upstream configured through interfaces carries no endpoint, so its id is what addresses it
        Model model = new Model();
        model.setName("model");
        Upstream legacy = new Upstream();
        legacy.setId("alpha");
        legacy.setEndpoint("ep1");
        Upstream interfaced = new Upstream();
        interfaced.setId("fireworks");
        interfaced.setBaseUrl("https://provider");
        interfaced.setInterfaces(Map.of(
                InterfaceType.ANTHROPIC_MESSAGES.getValue(), new UpstreamInterface()));
        model.setUpstreams(List.of(legacy, interfaced));

        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);

        assertEquals(interfaced, provider.get(model, null, "fireworks").next());
        assertEquals(legacy, provider.get(model, null, "alpha").next());
    }

    @Test
    public void testGet_UpstreamId_BlankIgnored() {
        Model model = new Model();
        model.setName("model");
        Upstream upstream1 = new Upstream();
        upstream1.setEndpoint("ep1");
        Upstream upstream2 = new Upstream();
        upstream2.setEndpoint("ep2");
        model.setUpstreams(List.of(upstream1, upstream2));

        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        UpstreamRoute route = provider.get(model, null, "   ");
        assertNotNull(route.next());
    }

    @Test
    public void testGet_CachedUpstreamMatchedById() {
        Model model = new Model();
        model.setName("model");
        Upstream upstream1 = new Upstream();
        upstream1.setId("alpha");
        upstream1.setEndpoint("shared-endpoint");
        upstream1.setTier(0);
        upstream1.setWeight(2);
        Upstream upstream2 = new Upstream();
        upstream2.setId("beta");
        upstream2.setEndpoint("shared-endpoint");
        upstream2.setTier(0);
        upstream2.setWeight(2);
        model.setUpstreams(List.of(upstream1, upstream2));

        UpstreamRouteProvider provider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        CacheBreakpointContext cacheBreakpointContext = new CacheBreakpointContext(List.of(), Map.of(), CachePolicy.CACHE_PRIORITY);
        CachedUpstreamEntry entry = new CachedUpstreamEntry("shared-endpoint", "beta", "prefix", null);
        when(upstreamCacheService.getCacheEntry(eq(cacheBreakpointContext), eq(model))).thenReturn(entry);

        UpstreamRoute route = provider.get(model, cacheBreakpointContext);
        Upstream result = route.next();

        assertEquals(upstream2, result);
    }
}
