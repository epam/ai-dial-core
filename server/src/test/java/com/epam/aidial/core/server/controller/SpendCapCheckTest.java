package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.limiter.SpendCap;
import com.epam.aidial.core.server.limiter.SpendCaps;
import com.epam.aidial.core.server.limiter.SpendReadException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpendCapCheckTest {

    private static final HttpException SPEND_UNAVAILABLE = new HttpException(HttpStatus.TOO_MANY_REQUESTS, "unavailable");
    private static final HttpException OTHER_LIMIT = new HttpException(HttpStatus.TOO_MANY_REQUESTS, "other limit");

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private RateLimiter rateLimiter;

    private final RecordingAnswers answers = new RecordingAnswers();

    @BeforeEach
    void setUp() {
        Model model = new Model();
        model.setName("model");
        when(context.getDeployment()).thenReturn(model);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
    }

    private static SpendCaps caps(String used) {
        return SpendCaps.of(List.of(new SpendCap(CalendarPeriod.DAY, new BigDecimal(used), BigDecimal.TEN, 0)));
    }

    @Test
    void verify_passedCheckReturnsCaps() {
        SpendCaps caps = caps("8");
        when(rateLimiter.limitWithSpendCaps(any(), any())).thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS.withSpendCaps(caps)));

        Future<SpendCaps> result = SpendCapCheck.verify(proxy, context, true, answers);

        assertSame(caps, result.result());
        assertEquals(List.of("rejectOtherLimit"), answers.calls);
    }

    @Test
    void verify_reachedCapWinsWithTheLimiterMessage() {
        RateLimitResult rejected = new RateLimitResult(HttpStatus.TOO_MANY_REQUESTS, "cost", "You've exceeded your daily cost limit", 10)
                .withSpendCaps(caps("10"));
        when(rateLimiter.limitWithSpendCaps(any(), any())).thenReturn(Future.succeededFuture(rejected));

        Future<SpendCaps> result = SpendCapCheck.verify(proxy, context, true, answers);

        assertTrue(result.failed());
        assertEquals(List.of("spendCapReached:You've exceeded your daily cost limit"), answers.calls);
    }

    @Test
    void verify_otherLimitIsLeftToTheRoute() {
        RateLimitResult rejected = new RateLimitResult(HttpStatus.TOO_MANY_REQUESTS, "tokens", "tokens", 10).withSpendCaps(caps("8"));
        when(rateLimiter.limitWithSpendCaps(any(), any())).thenReturn(Future.succeededFuture(rejected));

        Future<SpendCaps> result = SpendCapCheck.verify(proxy, context, true, answers);

        assertSame(OTHER_LIMIT, result.cause());
    }

    @Test
    void verify_unreadableSpendFailsClosedForCostLimitedCaller() {
        when(rateLimiter.limitWithSpendCaps(any(), any())).thenReturn(Future.failedFuture(new SpendReadException(new IllegalStateException())));
        when(rateLimiter.hasCostLimit(context)).thenReturn(true);

        Future<SpendCaps> result = SpendCapCheck.verify(proxy, context, true, answers);

        assertSame(SPEND_UNAVAILABLE, result.cause());
    }

    @Test
    void verify_unreadableSpendWithoutCostLimitIsAnOtherFailure() {
        SpendReadException error = new SpendReadException(new IllegalStateException());
        when(rateLimiter.limitWithSpendCaps(any(), any())).thenReturn(Future.failedFuture(error));
        when(rateLimiter.hasCostLimit(context)).thenReturn(false);

        Future<SpendCaps> result = SpendCapCheck.verify(proxy, context, true, answers);

        assertSame(error, result.cause());
        assertEquals(List.of("limitCheckFailed"), answers.calls);
    }

    @Test
    void verify_translatedReadsCapsAndFailsOpen() {
        when(rateLimiter.readSpendCaps(context)).thenReturn(Future.failedFuture(new IllegalStateException()));

        Future<SpendCaps> result = SpendCapCheck.verify(proxy, context, false, answers);

        assertSame(SpendCaps.EMPTY, result.result());
        assertTrue(answers.calls.isEmpty());
    }

    @Test
    void verify_translatedRejectsReachedCapWithoutLimiterMessage() {
        when(rateLimiter.readSpendCaps(context)).thenReturn(Future.succeededFuture(caps("10")));

        Future<SpendCaps> result = SpendCapCheck.verify(proxy, context, false, answers);

        assertTrue(result.failed());
        assertEquals(List.of("spendCapReached:null"), answers.calls);
    }

    private static class RecordingAnswers implements SpendCapCheck.Answers {

        private final List<String> calls = new ArrayList<>();

        @Override
        public HttpException spendCapReached(SpendCap cap, @Nullable String displayMessage, long nowMillis) {
            calls.add("spendCapReached:" + displayMessage);
            return new HttpException(HttpStatus.TOO_MANY_REQUESTS, "reached");
        }

        @Override
        public HttpException spendUnavailable() {
            calls.add("spendUnavailable");
            return SPEND_UNAVAILABLE;
        }

        @Override
        public Throwable limitCheckFailed(Throwable error) {
            calls.add("limitCheckFailed");
            return error;
        }

        @Override
        public void rejectOtherLimit(RateLimitResult result) {
            calls.add("rejectOtherLimit");
            if (result.status() != HttpStatus.OK) {
                throw OTHER_LIMIT;
            }
        }
    }
}
