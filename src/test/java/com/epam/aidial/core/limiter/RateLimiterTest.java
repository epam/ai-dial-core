package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.security.ExtractedClaims;
import com.epam.aidial.core.security.IdentityProvider;
import com.epam.aidial.core.util.HttpStatus;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RateLimiterTest {

    @Mock
    private HttpServerRequest request;

    @Mock
    private TestSpan currentSpan;

    @Mock
    private SpanContext parentSpanContext;

    @Mock
    private SpanContext spanContext;

    private RateLimiter rateLimiter;

    @BeforeEach
    public void beforeEach() {
        rateLimiter = new RateLimiter();
    }

    @Test
    public void testRegister_TraceNotFound() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, new Key(), IdentityProvider.CLAIMS_WITH_EMPTY_ROLES);
        try (MockedStatic<Span> mockedSpan = mockStatic(Span.class)) {
            mockedSpan.when(Span::current).thenReturn(currentSpan);
            when(currentSpan.getParentSpanContext()).thenReturn(parentSpanContext);
            when(currentSpan.getSpanContext()).thenReturn(spanContext);
            when(parentSpanContext.isRemote()).thenReturn(true);
            when(spanContext.getTraceId()).thenReturn("unknown-trace-id");

            assertFalse(rateLimiter.register(proxyContext));
        }
    }

    @Test
    public void testRegister_SuccessNoParentSpan() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES);
        try (MockedStatic<Span> mockedSpan = mockStatic(Span.class)) {
            mockedSpan.when(Span::current).thenReturn(currentSpan);
            when(currentSpan.getParentSpanContext()).thenReturn(parentSpanContext);
            when(currentSpan.getSpanContext()).thenReturn(spanContext);
            when(spanContext.getTraceId()).thenReturn("trace-id");

            assertTrue(rateLimiter.register(proxyContext));

            rateLimiter.unregister();

            // try to register again
            assertTrue(rateLimiter.register(proxyContext));
        }
    }

    @Test
    public void testRegister_SuccessParentSpanExists() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES);
        try (MockedStatic<Span> mockedSpan = mockStatic(Span.class)) {
            mockedSpan.when(Span::current).thenReturn(currentSpan);
            when(currentSpan.getParentSpanContext()).thenReturn(parentSpanContext);
            when(currentSpan.getSpanContext()).thenReturn(spanContext);
            when(spanContext.getTraceId()).thenReturn("trace-id");

            assertTrue(rateLimiter.register(proxyContext));

            // make a call with parent context
            when(parentSpanContext.isRemote()).thenReturn(true);

            assertTrue(rateLimiter.register(proxyContext));
        }
    }

    @Test
    public void testLimit_EntityNotFound() {
        ProxyContext proxyContext = new ProxyContext(new Config(), request, new Key(), IdentityProvider.CLAIMS_WITH_EMPTY_ROLES);
        try (MockedStatic<Span> mockedSpan = mockStatic(Span.class)) {
            mockedSpan.when(Span::current).thenReturn(currentSpan);
            when(currentSpan.getSpanContext()).thenReturn(spanContext);
            when(spanContext.getTraceId()).thenReturn("unknown-trace-id");

            RateLimitResult result = rateLimiter.limit(proxyContext);

            assertNotNull(result);
            assertEquals(HttpStatus.FORBIDDEN, result.status());
        }
    }

    @Test
    public void testLimit_SuccessUser() {
        ProxyContext proxyContext = new ProxyContext(new Config(), request, null, new ExtractedClaims("sub", Collections.emptyList(), "hash"));
        try (MockedStatic<Span> mockedSpan = mockStatic(Span.class)) {
            mockedSpan.when(Span::current).thenReturn(currentSpan);
            when(currentSpan.getParentSpanContext()).thenReturn(parentSpanContext);
            when(currentSpan.getSpanContext()).thenReturn(spanContext);
            when(spanContext.getTraceId()).thenReturn("trace-id");

            assertTrue(rateLimiter.register(proxyContext));

            RateLimitResult result = rateLimiter.limit(proxyContext);

            assertNotNull(result);
            assertEquals(HttpStatus.OK, result.status());
        }
    }

    @Test
    public void testLimit_ApiKeyLimitNotFound() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES);
        proxyContext.setDeployment(new Model());
        try (MockedStatic<Span> mockedSpan = mockStatic(Span.class)) {
            mockedSpan.when(Span::current).thenReturn(currentSpan);
            when(currentSpan.getParentSpanContext()).thenReturn(parentSpanContext);
            when(currentSpan.getSpanContext()).thenReturn(spanContext);
            when(spanContext.getTraceId()).thenReturn("trace-id");

            assertTrue(rateLimiter.register(proxyContext));

            RateLimitResult result = rateLimiter.limit(proxyContext);

            assertNotNull(result);
            assertEquals(HttpStatus.FORBIDDEN, result.status());
        }
    }

    @Test
    public void testLimit_ApiKeyLimitNegative() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        limit.setDay(-1);
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ProxyContext proxyContext = new ProxyContext(config, request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES);
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);
        try (MockedStatic<Span> mockedSpan = mockStatic(Span.class)) {
            mockedSpan.when(Span::current).thenReturn(currentSpan);
            when(currentSpan.getParentSpanContext()).thenReturn(parentSpanContext);
            when(currentSpan.getSpanContext()).thenReturn(spanContext);
            when(spanContext.getTraceId()).thenReturn("trace-id");

            assertTrue(rateLimiter.register(proxyContext));

            RateLimitResult result = rateLimiter.limit(proxyContext);

            assertNotNull(result);
            assertEquals(HttpStatus.FORBIDDEN, result.status());
        }
    }

    @Test
    public void testLimit_ApiKeySuccess() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ProxyContext proxyContext = new ProxyContext(config, request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES);
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);
        try (MockedStatic<Span> mockedSpan = mockStatic(Span.class)) {
            mockedSpan.when(Span::current).thenReturn(currentSpan);
            when(currentSpan.getSpanContext()).thenReturn(spanContext);
            when(currentSpan.getParentSpanContext()).thenReturn(parentSpanContext);
            when(currentSpan.getParentSpanContext()).thenReturn(parentSpanContext);
            when(spanContext.getTraceId()).thenReturn("trace-id");

            assertTrue(rateLimiter.register(proxyContext));

            RateLimitResult result = rateLimiter.limit(proxyContext);

            assertNotNull(result);
            assertEquals(HttpStatus.OK, result.status());
        }
    }

    interface TestSpan extends Span, ReadableSpan {

    }
}
