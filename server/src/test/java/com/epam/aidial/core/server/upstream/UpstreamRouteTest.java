package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.data.cache.CachedUpstreamEntry;
import com.epam.aidial.core.server.service.UpstreamCacheService;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UpstreamRouteTest {

    @Mock
    private Vertx vertx;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private UpstreamCacheService upstreamCacheService;

    @Mock
    private Random generator;

    @Test
    void testUpstreamRouteWithRetry() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, null, null, 1, 1, null, null, null),
                new Upstream("endpoint2", null, null, null, null, 1, 1, null, null, null),
                new Upstream("endpoint3", null, null, null, null, 1, 1, null, null, null),
                new Upstream("endpoint4", null, null, null, null, 1, 1, null, null, null)
        ));

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        CacheBreakpointContext cacheBreakpointContext = new CacheBreakpointContext(List.of(), Map.of(), CachePolicy.AVAILABILITY_PRIORITY);
        UpstreamRoute route = upstreamRouteProvider.get(model, cacheBreakpointContext);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(1, route.getAttemptCount());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(2, route.getAttemptCount());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(3, route.getAttemptCount());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        route.next();

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(4, route.getAttemptCount());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        assertThrows(HttpException.class, route::next);

        // verify route reach max attempts
        assertFalse(route.available());
        assertNull(route.get());
        assertEquals(4, route.getAttemptCount());
    }

    @Test
    void testUpstreamRouteWithRetry2() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, null, null, 1, 1, null, null, null),
                new Upstream("endpoint2", null, null, null, null, 1, 1, null, null, null)
        ));

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        CacheBreakpointContext cacheBreakpointContext = new CacheBreakpointContext(List.of(), Map.of(), CachePolicy.AVAILABILITY_PRIORITY);
        UpstreamRoute route = upstreamRouteProvider.get(model, cacheBreakpointContext);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(1, route.getAttemptCount());

        route.fail(HttpStatus.TOO_MANY_REQUESTS, 30);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(2, route.getAttemptCount());

        route.fail(HttpStatus.TOO_MANY_REQUESTS, 30);
        assertThrows(HttpException.class, route::next);

        assertFalse(route.available());
        assertNull(route.get());
        assertEquals(2, route.getAttemptCount());
    }

    @Test
    void testUpstreamRouteWithCachedUpstream() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, null, null, 1, 1, null, null, null),
                new Upstream("endpoint2", null, null, null, null, 1, 1, null, null, null)
        ));

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        CacheBreakpointContext cacheBreakpointContext = new CacheBreakpointContext(List.of(), Map.of(), CachePolicy.AVAILABILITY_PRIORITY);
        CachedUpstreamEntry entry = new CachedUpstreamEntry("endpoint2", null, "prefix", null);
        when(upstreamCacheService.getCacheEntry(eq(cacheBreakpointContext), eq(model))).thenReturn(entry);
        UpstreamRoute route = upstreamRouteProvider.get(model, cacheBreakpointContext);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertEquals(model.getUpstreams().get(1), route.get());
        assertEquals(1, route.getAttemptCount());

        route.fail(HttpStatus.TOO_MANY_REQUESTS, 30);
        assertEquals(model.getUpstreams().get(0), route.next());
    }

    @Test
    void testUpstreamRouteWithCachedUpstream_CachePriority() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, null, null, 1, 1, null, null, null),
                new Upstream("endpoint2", null, null, null, null, 1, 1, null, null, null)
        ));

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        CacheBreakpointContext cacheBreakpointContext = new CacheBreakpointContext(List.of(), Map.of(), CachePolicy.CACHE_PRIORITY);
        CachedUpstreamEntry entry = new CachedUpstreamEntry("endpoint2", null, "prefix", null);
        when(upstreamCacheService.getCacheEntry(eq(cacheBreakpointContext), eq(model))).thenReturn(entry);
        UpstreamRoute route = upstreamRouteProvider.get(model, cacheBreakpointContext);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertEquals(model.getUpstreams().get(1), route.get());
        assertEquals(1, route.getAttemptCount());

        route.fail(HttpStatus.TOO_MANY_REQUESTS, 30);
        assertEquals(model.getUpstreams().get(1), route.next());
    }

    @Test
    void testSuccess_UpstreamCache() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, null, null, 1, 1, null, null, null),
                new Upstream("endpoint2", null, null, null, null, 1, 1, null, null, null)
        ));

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        CacheBreakpointContext cacheBreakpointContext = new CacheBreakpointContext(List.of("prefix"), Map.of("prefix", "hash"), CachePolicy.CACHE_PRIORITY);
        CachedUpstreamEntry entry = new CachedUpstreamEntry("endpoint2", null, "prefix", null);
        when(upstreamCacheService.getCacheEntry(eq(cacheBreakpointContext), eq(model))).thenReturn(entry);
        UpstreamRoute route = upstreamRouteProvider.get(model, cacheBreakpointContext);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertEquals(model.getUpstreams().get(1), route.get());
        assertEquals(1, route.getAttemptCount());

        HttpClientResponse response = mock(HttpClientResponse.class);
        when(response.getHeader(Proxy.HEADER_CACHE_BREAKPOINT_PATH)).thenReturn("prefix");
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            callable.call();
            return Future.succeededFuture();
        });

        route.succeed(response, model);

        verify(upstreamCacheService).updateEntry(anyString(), any(CachedUpstreamEntry.class), eq(model), any());
    }

    @Test
    void testSuccess_UpstreamCache_NestedContentBlockPath() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, null, null, 1, 1, null, null, null),
                new Upstream("endpoint2", null, null, null, null, 1, 1, null, null, null)
        ));

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        String nestedPath = "prefix.body.messages[1].content[2]";
        CacheBreakpointContext cacheBreakpointContext =
                new CacheBreakpointContext(List.of(nestedPath), Map.of(nestedPath, "hash"), CachePolicy.CACHE_PRIORITY);
        CachedUpstreamEntry entry = new CachedUpstreamEntry("endpoint2", null, nestedPath, null);
        when(upstreamCacheService.getCacheEntry(eq(cacheBreakpointContext), eq(model))).thenReturn(entry);
        UpstreamRoute route = upstreamRouteProvider.get(model, cacheBreakpointContext);
        assertNotNull(route.next());

        assertEquals(model.getUpstreams().get(1), route.get());

        HttpClientResponse response = mock(HttpClientResponse.class);
        when(response.getHeader(Proxy.HEADER_CACHE_BREAKPOINT_PATH)).thenReturn(nestedPath);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            callable.call();
            return Future.succeededFuture();
        });

        route.succeed(response, model);

        verify(upstreamCacheService).updateEntry(eq("hash"), any(CachedUpstreamEntry.class), eq(model), any());
    }

    @Test
    void testSuccess_UpstreamCache_PrefixNotFound() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, null, null, 1, 1, null, null, null),
                new Upstream("endpoint2", null, null, null, null, 1, 1, null, null, null)
        ));

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        CacheBreakpointContext cacheBreakpointContext = new CacheBreakpointContext(List.of("prefix"), Map.of("prefix", "hash"), CachePolicy.CACHE_PRIORITY);
        CachedUpstreamEntry entry = new CachedUpstreamEntry("endpoint2", null, "prefix", null);
        when(upstreamCacheService.getCacheEntry(eq(cacheBreakpointContext), eq(model))).thenReturn(entry);
        UpstreamRoute route = upstreamRouteProvider.get(model, cacheBreakpointContext);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertEquals(model.getUpstreams().get(1), route.get());
        assertEquals(1, route.getAttemptCount());

        HttpClientResponse response = mock(HttpClientResponse.class);
        when(response.getHeader(Proxy.HEADER_CACHE_BREAKPOINT_PATH)).thenReturn("unknown");


        route.succeed(response, model);

        verify(taskExecutor, never()).submit(any(Callable.class));
        verify(upstreamCacheService, never()).updateEntry(isNull(), any(CachedUpstreamEntry.class), any(Model.class), any());
    }

    @Test
    void testSuccess_UpstreamCache_NoCacheBreakpointContext() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, null, null, 1, 1, null, null, null),
                new Upstream("endpoint2", null, null, null, null, 1, 0, null, null, null)
        ));

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, taskExecutor, () -> generator, upstreamCacheService);
        UpstreamRoute route = upstreamRouteProvider.get(model, null);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertEquals(model.getUpstreams().get(1), route.get());
        assertEquals(1, route.getAttemptCount());

        HttpClientResponse response = mock(HttpClientResponse.class);
        when(response.getHeader(Proxy.HEADER_CACHE_BREAKPOINT_PATH)).thenReturn("prefix");

        route.succeed(response, model);

        verify(taskExecutor, never()).submit(any(Callable.class));
        verify(upstreamCacheService, never()).updateEntry(isNull(), any(CachedUpstreamEntry.class), any(Model.class), any());
    }
}
