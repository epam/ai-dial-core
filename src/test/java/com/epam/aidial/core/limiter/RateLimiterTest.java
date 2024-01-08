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
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RateLimiterTest {

    @Mock
    private HttpServerRequest request;

    @Mock
    private TestSpan span;

    @Mock
    private SpanContext spanContext;

    private RateLimiter rateLimiter;

    @BeforeEach
    public void beforeEach() {
        rateLimiter = new RateLimiter();
        when(span.getSpanContext()).thenReturn(spanContext);
    }

    @Test
    public void testRegister_SuccessNoParentSpan() {
        when(spanContext.getTraceId()).thenReturn("trace-id");
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        key.setProject("project");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, span);

        assertFalse(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());

        rateLimiter.unregister(proxyContext);

        // try to register again
        assertFalse(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());
    }

    @Test
    public void testRegister_SuccessWithNullRole() {
        when(spanContext.getTraceId()).thenReturn("trace-id");
        Key key = new Key();
        key.setKey("key");
        key.setProject("project");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, span);

        assertFalse(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());

        rateLimiter.unregister(proxyContext);

        // try to register again
        assertFalse(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());
    }

    @Test
    public void testRegister_SuccessParentSpanExists() {
        when(spanContext.getTraceId()).thenReturn("trace-id");
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        key.setProject("project");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, span);

        assertFalse(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());

        SpanContext parentSpanContext = mock(SpanContext.class);
        when(parentSpanContext.getSpanId()).thenReturn("parent-id");
        when(span.getParentSpanContext()).thenReturn(parentSpanContext);

        assertTrue(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());
    }

    @Test
    public void testLimit_EntityNotFound() {
        when(spanContext.getTraceId()).thenReturn("unknown-trace-id");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, new Key(), IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, span);

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.FORBIDDEN, result.status());
    }

    @Test
    public void testLimit_SuccessUser() {
        when(spanContext.getTraceId()).thenReturn("trace-id");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, null, new ExtractedClaims("sub", Collections.emptyList(), "hash"), span);

        assertFalse(rateLimiter.register(proxyContext));

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.status());
    }

    @Test
    public void testLimit_ApiKeyLimitNotFound() {
        when(spanContext.getTraceId()).thenReturn("trace-id");
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, span);
        proxyContext.setDeployment(new Model());


        assertFalse(rateLimiter.register(proxyContext));

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.FORBIDDEN, result.status());

    }


    @Test
    public void testLimit_ApiKeyLimitNotFoundWithNullRole() {
        when(spanContext.getTraceId()).thenReturn("trace-id");
        Key key = new Key();
        key.setKey("key");
        key.setProject("project");
        ProxyContext proxyContext = new ProxyContext(new Config(), request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, span);
        proxyContext.setDeployment(new Model());


        assertFalse(rateLimiter.register(proxyContext));

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.FORBIDDEN, result.status());

    }

    @Test
    public void testLimit_ApiKeyLimitNegative() {
        when(spanContext.getTraceId()).thenReturn("trace-id");
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        limit.setDay(-1);
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ProxyContext proxyContext = new ProxyContext(config, request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, span);
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);


        assertFalse(rateLimiter.register(proxyContext));

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.FORBIDDEN, result.status());

    }

    @Test
    public void testLimit_ApiKeySuccess() {
        when(spanContext.getTraceId()).thenReturn("trace-id");
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ProxyContext proxyContext = new ProxyContext(config, request, key, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, span);
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        assertFalse(rateLimiter.register(proxyContext));

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.status());

    }

    private interface TestSpan extends Span, ReadableSpan {

    }
}
