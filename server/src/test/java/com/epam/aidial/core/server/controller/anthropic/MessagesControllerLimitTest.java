package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.LimitBreach;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.limiter.SpendCap;
import com.epam.aidial.core.server.limiter.SpendCaps;
import com.epam.aidial.core.server.limiter.SpendReadException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A passthrough request of a user with a cost limit fails closed on an unreadable spend record; any other failure of the check
 * is a server error the client retries. A translated one only reads the spend caps:
 * the inner call the translator makes back to Core enforces the limits.
 */
@ExtendWith(MockitoExtension.class)
class MessagesControllerLimitTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private RateLimiter rateLimiter;

    @Test
    void testVerifyLimit_failsClosedWhenSpendIsUnreadable() {
        Model model = new Model();
        model.setName("claude-spend");
        when(context.getDeployment()).thenReturn(model);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(rateLimiter.limitWithSpendCaps(any(), any()))
                .thenReturn(Future.failedFuture(new SpendReadException(new IllegalStateException("redis down"))));
        when(rateLimiter.hasCostLimit(context)).thenReturn(true);

        Future<Void> result = new MessagesController(proxy, context).verifyLimit();

        assertTrue(result.failed());
        HttpException exception = assertInstanceOf(HttpException.class, result.cause());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        assertEquals("false", exception.getHeaders().get("x-should-retry"));
        assertEquals("fetch_error", exception.getHeaders().get("anthropic-ratelimit-unified-overage-disabled-reason"));
        assertFalse(exception.getHeaders().keySet().stream()
                .anyMatch(name -> name.toLowerCase().startsWith("anthropic-ratelimit-")
                        && !name.equalsIgnoreCase("anthropic-ratelimit-unified-overage-disabled-reason")));
    }

    @Test
    void testVerifyLimit_unreadableSpendWithoutCostLimitIsRetried() {
        Model model = new Model();
        model.setName("claude-spend");
        when(context.getDeployment()).thenReturn(model);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(rateLimiter.limitWithSpendCaps(any(), any()))
                .thenReturn(Future.failedFuture(new SpendReadException(new IllegalStateException("redis down"))));
        when(rateLimiter.hasCostLimit(context)).thenReturn(false);

        Future<Void> result = new MessagesController(proxy, context).verifyLimit();

        // no spend limit to blame: a server error, which clients retry
        assertRetriedServerError(result);
    }

    @Test
    void testVerifyLimit_otherFailureOfCostLimitedCallerIsRetried() {
        Model model = new Model();
        model.setName("claude-spend");
        when(context.getDeployment()).thenReturn(model);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(rateLimiter.limitWithSpendCaps(any(), any())).thenReturn(Future.failedFuture(new IllegalStateException("token record timeout")));

        Future<Void> result = new MessagesController(proxy, context).verifyLimit();

        // the spend was read, so a failure elsewhere in the check must not end the session with a billing error
        assertRetriedServerError(result);
        verify(rateLimiter, never()).hasCostLimit(any());
    }

    @Test
    void testVerifyLimit_reachedSpendCapWinsOverTokenLimit() {
        Model model = new Model();
        model.setName("claude-spend");
        when(context.getDeployment()).thenReturn(model);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        SpendCap month = new SpendCap(CalendarPeriod.MONTH, BigDecimal.TEN, BigDecimal.TEN, System.currentTimeMillis() + 3_600_000);
        LimitBreach tokens = new LimitBreach(LimitBreach.Kind.TOKENS, LimitBreach.Window.MINUTE, 30, -1);
        RateLimitResult rejected = new RateLimitResult(HttpStatus.TOO_MANY_REQUESTS, "tokens", "tokens", 30, tokens, new SpendCaps(List.of(month), month));
        when(rateLimiter.limitWithSpendCaps(any(), any())).thenReturn(Future.succeededFuture(rejected));

        Future<Void> result = new MessagesController(proxy, context).verifyLimit();

        HttpException exception = assertInstanceOf(HttpException.class, result.cause());
        assertTrue(exception.getMessage().contains("\"billing_error\""), exception.getMessage());
        assertEquals("false", exception.getHeaders().get("x-should-retry"));
    }

    @Test
    void testVerifyLimit_translatedReadsSpendWithoutEnforcing() {
        when(context.getDeployment()).thenReturn(translatedModel());
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(rateLimiter.readSpendCaps(any())).thenReturn(Future.succeededFuture(SpendCaps.EMPTY));

        Future<Void> result = new MessagesController(proxy, context).verifyLimit();

        assertTrue(result.succeeded());
        verify(rateLimiter, never()).limitWithSpendCaps(any(), any());
        verify(rateLimiter, never()).limit(any(), any());
    }

    @Test
    void testVerifyLimit_translatedRejectsOnceSpendCapIsReached() {
        when(context.getDeployment()).thenReturn(translatedModel());
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        SpendCap day = new SpendCap(CalendarPeriod.DAY, BigDecimal.TEN, BigDecimal.TEN, System.currentTimeMillis() + 60_000);
        when(rateLimiter.readSpendCaps(any())).thenReturn(Future.succeededFuture(new SpendCaps(List.of(day), day)));

        Future<Void> result = new MessagesController(proxy, context).verifyLimit();

        assertTrue(result.failed());
        HttpException exception = assertInstanceOf(HttpException.class, result.cause());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        assertTrue(exception.getMessage().contains("\"billing_error\""), exception.getMessage());
    }

    @Test
    void testVerifyLimit_translatedLetsRequestThroughWhenSpendIsUnreadable() {
        when(context.getDeployment()).thenReturn(translatedModel());
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(rateLimiter.readSpendCaps(any())).thenReturn(Future.failedFuture(new IllegalStateException("redis down")));

        Future<Void> result = new MessagesController(proxy, context).verifyLimit();

        // the inner call the translator makes back to Core enforces the limits, so this one must not fail closed
        assertTrue(result.succeeded());
    }

    private static void assertRetriedServerError(Future<Void> result) {
        assertTrue(result.failed());
        HttpException exception = assertInstanceOf(HttpException.class, result.cause());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
        assertEquals("true", exception.getHeaders().get("x-should-retry"));
        assertTrue(exception.getMessage().contains("\"api_error\""), exception.getMessage());
    }

    private static Model translatedModel() {
        DeploymentInterface anthropic = new DeploymentInterface();
        anthropic.setMode(InterfaceMode.TRANSLATOR);
        Model model = new Model();
        model.setName("claude-translated");
        model.setInterfaces(Map.of(InterfaceType.ANTHROPIC_MESSAGES.getValue(), anthropic));
        return model;
    }
}
