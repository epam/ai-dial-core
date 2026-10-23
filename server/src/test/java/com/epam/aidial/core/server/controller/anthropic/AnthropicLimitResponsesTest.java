package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.data.AnthropicUsageData;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.LimitBreach;
import com.epam.aidial.core.server.limiter.LimitBreach.Kind;
import com.epam.aidial.core.server.limiter.LimitBreach.Window;
import com.epam.aidial.core.server.limiter.SpendCap;
import com.epam.aidial.core.server.limiter.SpendCaps;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicLimitResponsesTest {

    private static final long RESET = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli();
    private static final long NOW = Instant.parse("2026-10-23T20:00:00.500Z").toEpochMilli();
    private static final String PREFIX = "anthropic-ratelimit-";

    private static SpendCap cap(String used, String total) {
        return new SpendCap(CalendarPeriod.DAY, new BigDecimal(used), new BigDecimal(total), RESET);
    }

    private static SpendCap cap(CalendarPeriod period, String used, String total, String resetsAt) {
        return new SpendCap(period, new BigDecimal(used), new BigDecimal(total), Instant.parse(resetsAt).toEpochMilli());
    }

    private static MultiMap spendHeaders(String used, String total) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        AnthropicLimitResponses.putSpendHeaders(headers, cap(used, total));
        return headers;
    }

    private static void assertAllowed(MultiMap headers, String utilization, String threshold) {
        String status = threshold == null ? "allowed" : "allowed_warning";
        String reset = String.valueOf(RESET / 1000);
        assertEquals(status, headers.get("anthropic-ratelimit-unified-status"));
        assertEquals("overage", headers.get("anthropic-ratelimit-unified-representative-claim"));
        assertEquals(status, headers.get("anthropic-ratelimit-unified-overage-status"));
        assertEquals(utilization, headers.get("anthropic-ratelimit-unified-overage-utilization"));
        assertEquals(threshold, headers.get("anthropic-ratelimit-unified-overage-surpassed-threshold"));
        assertEquals(reset, headers.get("anthropic-ratelimit-unified-reset"));
        assertEquals(reset, headers.get("anthropic-ratelimit-unified-overage-reset"));
    }

    private static void assertNoRateLimitHeaders(HttpException exception) {
        assertTrue(exception.getHeaders().keySet().stream().noneMatch(name -> name.toLowerCase().startsWith(PREFIX)));
    }

    @Test
    void putSpendHeaders_belowThreshold() {
        assertAllowed(spendHeaders("5", "10"), "0.50", null);
    }

    @Test
    void putSpendHeaders_justBelowThreshold() {
        assertAllowed(spendHeaders("7.499", "10"), "0.74", null);
    }

    @Test
    void putSpendHeaders_atSeventyFive() {
        assertAllowed(spendHeaders("7.5", "10"), "0.75", "0.75");
    }

    @Test
    void putSpendHeaders_eightyTwo() {
        assertAllowed(spendHeaders("8.2", "10"), "0.82", "0.75");
    }

    @Test
    void putSpendHeaders_ninetySix() {
        assertAllowed(spendHeaders("9.6", "10"), "0.96", "0.95");
    }

    @Test
    void putSpendHeaders_capsAtNinetyNine() {
        assertAllowed(spendHeaders("9.997", "10"), "0.99", "0.95");
    }

    @Test
    void stripUpstreamRateLimitHeaders_removesOnlyRateLimitHeaders() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Anthropic-RateLimit-Tokens-Remaining", "5");
        headers.add("anthropic-ratelimit-unified-status", "allowed");
        headers.add("content-type", "application/json");
        headers.add("request-id", "r1");

        AnthropicLimitResponses.stripUpstreamRateLimitHeaders(headers);

        assertEquals(2, headers.size());
        assertEquals("application/json", headers.get("content-type"));
        assertEquals("r1", headers.get("request-id"));
    }

    @Test
    void spendCapReached_exactShape() throws Exception {
        HttpException exception = AnthropicLimitResponses.spendCapReached(cap("10", "10"), NOW);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        var headers = exception.getHeaders();
        String reset = String.valueOf(RESET / 1000);
        assertEquals("14400", headers.get("retry-after"));
        assertEquals("false", headers.get("x-should-retry"));
        assertEquals("rejected", headers.get("anthropic-ratelimit-unified-status"));
        assertEquals(reset, headers.get("anthropic-ratelimit-unified-reset"));
        assertEquals(reset, headers.get("anthropic-ratelimit-unified-overage-reset"));
        assertEquals("1", headers.get("anthropic-ratelimit-unified-overage-utilization"));
        assertEquals("1", headers.get("anthropic-ratelimit-unified-overage-surpassed-threshold"));
        assertEquals("daily", headers.get("anthropic-ratelimit-unified-overage-period"));
        assertEquals("org_spend_cap_reached", headers.get("anthropic-ratelimit-unified-overage-disabled-reason"));
        assertEquals("application/json", headers.get("content-type"));
        assertFalse(headers.containsKey("anthropic-ratelimit-unified-representative-claim"));
        assertFalse(headers.containsKey("anthropic-ratelimit-unified-overage-status"));
        JsonNode expected = ProxyUtil.MAPPER.readTree(
                "{\"type\":\"error\",\"error\":{\"type\":\"billing_error\","
                        + "\"message\":\"DIAL spend limit reached (daily; resets 2026-10-24 00:00 UTC)\"}}");
        assertEquals(expected, ProxyUtil.MAPPER.readTree(exception.getMessage()));
    }

    @Test
    void spendUnavailable_exactShape() throws Exception {
        HttpException exception = AnthropicLimitResponses.spendUnavailable();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        var headers = exception.getHeaders();
        assertEquals(3, headers.size());
        assertEquals("false", headers.get("x-should-retry"));
        assertEquals("fetch_error", headers.get("anthropic-ratelimit-unified-overage-disabled-reason"));
        assertEquals("application/json", headers.get("content-type"));
        JsonNode body = ProxyUtil.MAPPER.readTree(exception.getMessage());
        assertEquals("billing_error", body.at("/error/type").asText());
        assertEquals("DIAL could not read your spend limit; request rejected", body.at("/error/message").asText());
    }

    @Test
    void limitReached_rollingTokens() throws Exception {
        HttpException exception = AnthropicLimitResponses.limitReached(new LimitBreach(Kind.TOKENS, Window.MINUTE, 4), "claude-sonnet", NOW);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        assertEquals("true", exception.getHeaders().get("x-should-retry"));
        assertEquals("4", exception.getHeaders().get("retry-after"));
        assertEquals("application/json", exception.getHeaders().get("content-type"));
        assertNoRateLimitHeaders(exception);
        JsonNode body = ProxyUtil.MAPPER.readTree(exception.getMessage());
        assertEquals("error", body.get("type").asText());
        assertEquals("rate_limit_error", body.at("/error/type").asText());
        assertEquals("DIAL per-minute token limit for claude-sonnet reached; retry in 4 s", body.at("/error/message").asText());
    }

    @Test
    void limitReached_rollingCost() throws Exception {
        HttpException exception = AnthropicLimitResponses.limitReached(new LimitBreach(Kind.COST, Window.MINUTE, 4), "claude-sonnet", NOW);

        JsonNode body = ProxyUtil.MAPPER.readTree(exception.getMessage());
        assertEquals("DIAL per-minute spend limit reached; retry in 4 s", body.at("/error/message").asText());
    }

    @Test
    void limitReached_calendarRequests() throws Exception {
        long now = Instant.parse("2026-10-23T20:00:00Z").toEpochMilli();
        HttpException exception = AnthropicLimitResponses.limitReached(new LimitBreach(Kind.REQUESTS, Window.DAY, 14400), "claude-sonnet", now);

        assertEquals("false", exception.getHeaders().get("x-should-retry"));
        assertEquals("14400", exception.getHeaders().get("retry-after"));
        JsonNode body = ProxyUtil.MAPPER.readTree(exception.getMessage());
        assertEquals("DIAL daily request limit for claude-sonnet reached (resets 2026-10-24 00:00 UTC)", body.at("/error/message").asText());
    }

    @Test
    void limitReached_calendarCostWindow() throws Exception {
        long now = Instant.parse("2026-10-23T20:00:00Z").toEpochMilli();
        HttpException exception = AnthropicLimitResponses.limitReached(new LimitBreach(Kind.COST, Window.DAY, 14400), "claude-sonnet", now);

        assertEquals("false", exception.getHeaders().get("x-should-retry"));
        JsonNode body = ProxyUtil.MAPPER.readTree(exception.getMessage());
        assertEquals("DIAL daily spend limit reached (resets 2026-10-24 00:00 UTC)", body.at("/error/message").asText());
        assertEquals("rate_limit_error", body.at("/error/type").asText());
    }

    @Test
    void buildUsage_centsPercentsAndActiveRow() {
        SpendCap day = cap(CalendarPeriod.DAY, "8.2", "10", "2026-10-24T00:00:00Z");
        SpendCap month = cap(CalendarPeriod.MONTH, "271.40", "500", "2026-11-01T00:00:00Z");

        AnthropicUsageData usage = AnthropicLimitResponses.buildUsage(new SpendCaps(List.of(day, month), day));

        AnthropicUsageData.ExtraUsage extra = usage.getExtraUsage();
        assertTrue(extra.isEnabled());
        assertEquals(1000, extra.getMonthlyLimit());
        assertEquals(820, extra.getUsedCredits());
        assertEquals(82, extra.getUtilization());
        assertEquals("USD", extra.getCurrency());
        assertEquals(2, usage.getLimits().size());
        AnthropicUsageData.UsageLimit dayRow = usage.getLimits().get(0);
        assertEquals("spend", dayRow.getKind());
        assertEquals("daily", dayRow.getGroup());
        assertEquals(82, dayRow.getPercent());
        assertEquals("warning", dayRow.getSeverity());
        assertTrue(dayRow.isActive());
        assertEquals("2026-10-24T00:00:00.000Z", dayRow.getResetsAt());
        AnthropicUsageData.UsageLimit monthRow = usage.getLimits().get(1);
        assertEquals("monthly", monthRow.getGroup());
        assertEquals(54, monthRow.getPercent());
        assertEquals("normal", monthRow.getSeverity());
        assertFalse(monthRow.isActive());
        assertEquals("2026-11-01T00:00:00.000Z", monthRow.getResetsAt());
    }

    private static AnthropicUsageData.UsageLimit onlyRow(String used) {
        SpendCap day = cap(CalendarPeriod.DAY, used, "10", "2026-10-24T00:00:00Z");
        return AnthropicLimitResponses.buildUsage(new SpendCaps(List.of(day), day)).getLimits().get(0);
    }

    @Test
    void buildUsage_severities() {
        assertEquals("critical", onlyRow("9.6").getSeverity());
        AnthropicUsageData.UsageLimit normal = onlyRow("7.49");
        assertEquals("normal", normal.getSeverity());
        assertEquals(74, normal.getPercent());
    }

    @Test
    void buildUsage_overLimitUtilizationAbove100() {
        SpendCap day = cap(CalendarPeriod.DAY, "10.5", "10", "2026-10-24T00:00:00Z");

        AnthropicUsageData usage = AnthropicLimitResponses.buildUsage(new SpendCaps(List.of(day), day));

        assertEquals(105, usage.getExtraUsage().getUtilization());
        assertEquals(105, usage.getLimits().get(0).getPercent());
    }

    @Test
    void buildUsage_noCap() throws Exception {
        AnthropicUsageData usage = AnthropicLimitResponses.buildUsage(SpendCaps.EMPTY);

        assertNull(usage.getExtraUsage());
        assertEquals("{\"limits\":[]}", ProxyUtil.MAPPER.writeValueAsString(usage));
    }

    @Test
    void headersAndUsageAgree() {
        SpendCap day = cap(CalendarPeriod.DAY, "3", "10", "2026-10-24T00:00:00Z");
        SpendCap week = cap(CalendarPeriod.WEEK, "40", "50", "2026-10-26T00:00:00Z");
        SpendCaps caps = new SpendCaps(List.of(day, week), week);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();

        AnthropicLimitResponses.putSpendHeaders(headers, caps.reported());
        AnthropicUsageData usage = AnthropicLimitResponses.buildUsage(caps);

        AnthropicUsageData.UsageLimit active = usage.getLimits().stream().filter(AnthropicUsageData.UsageLimit::isActive).findFirst().orElseThrow();
        assertEquals(headers.get("anthropic-ratelimit-unified-overage-reset"), String.valueOf(Instant.parse(active.getResetsAt()).getEpochSecond()));
        assertEquals("weekly", active.getGroup());
        assertEquals("weekly", AnthropicLimitResponses.spendCapReached(week, NOW).getHeaders().get("anthropic-ratelimit-unified-overage-period"));
    }
}
