package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.limiter.CalendarPeriod;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexLimitResponsesTest {

    private static final String TOMORROW = "2026-10-24T00:00:00Z";
    private static final String NEXT_WEEK = "2026-10-26T00:00:00Z";
    private static final String NEXT_MONTH = "2026-11-01T00:00:00Z";
    private static final long NOW = Instant.parse("2026-10-23T20:00:00.500Z").toEpochMilli();

    private static SpendCap cap(CalendarPeriod period, String used, String total, String resetsAt) {
        return new SpendCap(period, new BigDecimal(used), new BigDecimal(total), Instant.parse(resetsAt).toEpochMilli());
    }

    private static MultiMap spendHeaders(SpendCap... windows) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        CodexLimitResponses.putSpendHeaders(headers, SpendCaps.of(List.of(windows)));
        return headers;
    }

    private static String seconds(String instant) {
        return String.valueOf(Instant.parse(instant).getEpochSecond());
    }

    @Test
    void putSpendHeaders_primaryAndSecondary() {
        MultiMap headers = spendHeaders(
                cap(CalendarPeriod.DAY, "8.2", "10", TOMORROW), cap(CalendarPeriod.MONTH, "40", "100", NEXT_MONTH));

        assertEquals(6, headers.size());
        assertEquals("82", headers.get("x-codex-primary-used-percent"));
        assertEquals("1440", headers.get("x-codex-primary-window-minutes"));
        assertEquals(seconds(TOMORROW), headers.get("x-codex-primary-reset-at"));
        assertEquals("40", headers.get("x-codex-secondary-used-percent"));
        assertEquals("43200", headers.get("x-codex-secondary-window-minutes"));
        assertEquals(seconds(NEXT_MONTH), headers.get("x-codex-secondary-reset-at"));
    }

    @Test
    void putSpendHeaders_primaryOnly() {
        MultiMap headers = spendHeaders(cap(CalendarPeriod.WEEK, "30", "100", NEXT_WEEK));

        assertEquals(3, headers.size());
        assertEquals("30", headers.get("x-codex-primary-used-percent"));
        assertEquals("10080", headers.get("x-codex-primary-window-minutes"));
        assertEquals(seconds(NEXT_WEEK), headers.get("x-codex-primary-reset-at"));
    }

    @Test
    void putSpendHeaders_percentRoundedDown() {
        assertEquals("74", spendHeaders(cap(CalendarPeriod.DAY, "7.499", "10", TOMORROW)).get("x-codex-primary-used-percent"));
    }

    @Test
    void putSpendHeaders_noCaps_nothing() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        CodexLimitResponses.putSpendHeaders(headers, SpendCaps.EMPTY);

        assertTrue(headers.isEmpty());
    }

    @Test
    void stripUpstreamCodexHeaders_removesOnlyCodexHeaders() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("X-Codex-Primary-Used-Percent", "5");
        headers.add("x-codex-active-limit", "codex");
        headers.add("content-type", "application/json");
        headers.add("x-request-id", "r1");

        CodexLimitResponses.stripUpstreamCodexHeaders(headers);

        assertEquals(2, headers.size());
        assertEquals("application/json", headers.get("content-type"));
        assertEquals("r1", headers.get("x-request-id"));
    }

    @Test
    void spendCapReached_exactShape() throws Exception {
        HttpException exception = CodexLimitResponses.spendCapReached(cap(CalendarPeriod.DAY, "10", "10", TOMORROW), NOW);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        var headers = exception.getHeaders();
        assertEquals(5, headers.size());
        assertEquals("14400", headers.get("retry-after"));
        assertEquals("100", headers.get("x-codex-primary-used-percent"));
        assertEquals("1440", headers.get("x-codex-primary-window-minutes"));
        assertEquals(seconds(TOMORROW), headers.get("x-codex-primary-reset-at"));
        assertEquals("application/json", headers.get("content-type"));
        JsonNode expected = ProxyUtil.MAPPER.readTree(
                "{\"error\":{\"type\":\"usage_limit_reached\",\"code\":\"429\","
                        + "\"message\":\"DIAL daily spend limit reached (resets 2026-10-24 00:00 UTC)\","
                        + "\"resets_at\":" + seconds(TOMORROW) + ",\"limit_window_minutes\":1440}}");
        assertEquals(expected, ProxyUtil.MAPPER.readTree(exception.getMessage()));
    }

    @Test
    void spendCapReached_monthWindow() throws Exception {
        HttpException exception = CodexLimitResponses.spendCapReached(cap(CalendarPeriod.MONTH, "120", "100", NEXT_MONTH), NOW);

        assertEquals("100", exception.getHeaders().get("x-codex-primary-used-percent"));
        JsonNode body = ProxyUtil.MAPPER.readTree(exception.getMessage());
        assertEquals(43200, body.at("/error/limit_window_minutes").asInt());
        assertEquals("DIAL monthly spend limit reached (resets 2026-11-01 00:00 UTC)", body.at("/error/message").asText());
    }

    @Test
    void spendUnavailable_exactShape() throws Exception {
        HttpException exception = CodexLimitResponses.spendUnavailable();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        var headers = exception.getHeaders();
        assertEquals(1, headers.size());
        assertEquals("application/json", headers.get("content-type"));
        assertNull(headers.get("retry-after"));
        JsonNode expected = ProxyUtil.MAPPER.readTree(
                "{\"error\":{\"code\":\"429\",\"message\":\"DIAL could not read your spend limit; request rejected\"}}");
        assertEquals(expected, ProxyUtil.MAPPER.readTree(exception.getMessage()));
    }
}
