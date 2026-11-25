package com.epam.aidial.core.server.token;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.tracing.BlockingCallTracer;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Span ids that cannot be told apart or that form a loop, e.g. the all-zero ids of a no-op tracer
 * (OpenTelemetry 1.67 with a disabled SDK).
 */
public class TokenStatsTrackerBadSpanIdsTest {

    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ZERO_SPAN_ID = "0000000000000000";

    /**
     * Fails the test instead of exhausting the heap if the walk over the ancestors never ends.
     */
    private static class BoundedList extends ArrayList<TokenStatsTracker.ModelTokenUsage> {
        private static final int LIMIT = 10_000;

        @Override
        public boolean add(TokenStatsTracker.ModelTokenUsage entry) {
            assertTrue(size() < LIMIT, "the ancestors are walked without end");
            return super.add(entry);
        }
    }

    private static TokenStatsTracker.TokenStats span(String parentSpanId, String deploymentName) {
        TokenStatsTracker.TokenStats stats = new TokenStatsTracker.TokenStats(new TokenUsage(), parentSpanId, deploymentName);
        stats.setUsagePerModel(new BoundedList());
        return stats;
    }

    private static TokenUsage usageCosting(String cost) {
        TokenUsage usage = new TokenUsage();
        usage.setAggCost(new BigDecimal(cost));
        return usage;
    }

    @Test
    public void testSpanThatIsItsOwnParentIsNotRolledUpForever() {
        TokenStatsTracker.TraceContext traceContext = new TokenStatsTracker.TraceContext();
        TokenStatsTracker.TokenStats loop = span("loop", "app");
        traceContext.getSpans().put("loop", loop);

        List<TokenStatsTracker.AggregatedCost> costs = traceContext.updateStats("loop", "gpt-4", usageCosting("1.0"));

        assertEquals(List.of(), costs);
        assertEquals(List.of(), loop.getUsagePerModel());
    }

    @Test
    public void testCycleOfSpansIsWalkedOnce() {
        TokenStatsTracker.TraceContext traceContext = new TokenStatsTracker.TraceContext();
        TokenStatsTracker.TokenStats first = span("second", "first-app");
        TokenStatsTracker.TokenStats second = span("first", "second-app");
        traceContext.getSpans().put("first", first);
        traceContext.getSpans().put("second", second);

        List<TokenStatsTracker.AggregatedCost> costs = traceContext.updateStats("first", "gpt-4", usageCosting("1.0"));

        assertEquals(List.of(new TokenStatsTracker.AggregatedCost("second-app", new BigDecimal("1.0"))), costs);
        assertEquals(1, second.getUsagePerModel().size());
        assertEquals(List.of(), first.getUsagePerModel());
    }

    @Test
    public void testZeroIdsAreNotTracked() {
        AsyncTaskExecutor taskExecutor = mock(AsyncTaskExecutor.class);
        RedissonClient redis = mock(RedissonClient.class);
        TokenStatsTracker tracker = new TokenStatsTracker(taskExecutor, redis, mock(LockService.class), "prefix",
                BlockingCallTracer.NOOP, Duration.ofHours(1), Duration.ofDays(2));
        ProxyContext root = mock(ProxyContext.class);
        when(root.getTraceId()).thenReturn(ZERO_TRACE_ID);
        when(root.getSpanId()).thenReturn(ZERO_SPAN_ID);
        when(root.isOriginalRequest()).thenReturn(true);
        ProxyContext nested = mock(ProxyContext.class);
        when(nested.getTraceId()).thenReturn(ZERO_TRACE_ID);
        when(nested.getSpanId()).thenReturn(ZERO_SPAN_ID);

        tracker.startSpan(root);
        tracker.startSpan(nested);
        tracker.unnameSpan(nested);
        assertEquals(TokenStatsTracker.UsageStats.EMPTY,
                tracker.updateDeploymentStats(ZERO_TRACE_ID, ZERO_SPAN_ID, "gpt-4", usageCosting("1.0")).result());
        assertEquals(TokenStatsTracker.UsageStats.EMPTY, tracker.getUsageStats(nested).result());
        tracker.endSpan(root);

        verifyNoInteractions(taskExecutor, redis);
    }
}
