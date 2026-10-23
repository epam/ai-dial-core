package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.data.AnthropicUsageData;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.LimitBreach;
import com.epam.aidial.core.server.limiter.SpendCap;
import com.epam.aidial.core.server.limiter.SpendCaps;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@UtilityClass
class AnthropicLimitResponses {

    private static final String RATE_LIMIT_PREFIX = "anthropic-ratelimit-";
    private static final String UNIFIED_PREFIX = RATE_LIMIT_PREFIX + "unified-";
    private static final String OVERAGE_PREFIX = UNIFIED_PREFIX + "overage-";
    private static final String CONTENT_TYPE = "content-type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String SHOULD_RETRY = "x-should-retry";
    private static final BigDecimal WARNING_RATIO = new BigDecimal("0.75");
    private static final BigDecimal CRITICAL_RATIO = new BigDecimal("0.95");
    private static final BigDecimal MAX_ALLOWED_UTILIZATION = new BigDecimal("0.99");
    private static final DateTimeFormatter MESSAGE_RESET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter USAGE_RESET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    void stripUpstreamRateLimitHeaders(MultiMap headers) {
        headers.names().stream()
                .filter(name -> name.regionMatches(true, 0, RATE_LIMIT_PREFIX, 0, RATE_LIMIT_PREFIX.length()))
                .toList()
                .forEach(headers::remove);
    }

    void putSpendHeaders(MultiMap headers, SpendCap cap) {
        BigDecimal ratio = cap.ratio();
        BigDecimal utilization = ratio.setScale(2, RoundingMode.DOWN).min(MAX_ALLOWED_UTILIZATION);
        String threshold = ratio.compareTo(CRITICAL_RATIO) >= 0 ? "0.95" : ratio.compareTo(WARNING_RATIO) >= 0 ? "0.75" : null;
        String status = threshold == null ? "allowed" : "allowed_warning";
        String reset = String.valueOf(cap.resetsAtMillis() / 1000);

        headers.set(UNIFIED_PREFIX + "status", status);
        headers.set(UNIFIED_PREFIX + "representative-claim", "overage");
        headers.set(OVERAGE_PREFIX + "status", status);
        headers.set(OVERAGE_PREFIX + "utilization", utilization.toPlainString());
        if (threshold != null) {
            headers.set(OVERAGE_PREFIX + "surpassed-threshold", threshold);
        }
        headers.set(UNIFIED_PREFIX + "reset", reset);
        headers.set(OVERAGE_PREFIX + "reset", reset);
    }

    HttpException spendCapReached(SpendCap cap, long nowMillis) {
        String reset = String.valueOf(cap.resetsAtMillis() / 1000);
        String period = periodName(cap.period());
        String message = "DIAL spend limit reached (" + period + "; resets " + formatReset(cap.resetsAtMillis()) + ")";
        return new HttpException(HttpStatus.TOO_MANY_REQUESTS, errorBody("billing_error", message), Map.ofEntries(
                Map.entry("retry-after", String.valueOf(Math.max(0, Math.ceilDiv(cap.resetsAtMillis() - nowMillis, 1000)))),
                Map.entry(SHOULD_RETRY, "false"),
                Map.entry(UNIFIED_PREFIX + "status", "rejected"),
                Map.entry(UNIFIED_PREFIX + "reset", reset),
                Map.entry(OVERAGE_PREFIX + "reset", reset),
                Map.entry(OVERAGE_PREFIX + "utilization", "1"),
                Map.entry(OVERAGE_PREFIX + "surpassed-threshold", "1"),
                Map.entry(OVERAGE_PREFIX + "period", period),
                Map.entry(OVERAGE_PREFIX + "disabled-reason", "org_spend_cap_reached"),
                Map.entry(CONTENT_TYPE, APPLICATION_JSON)));
    }

    HttpException spendUnavailable() {
        return new HttpException(HttpStatus.TOO_MANY_REQUESTS, errorBody("billing_error", "DIAL could not read your spend limit; request rejected"), Map.of(
                SHOULD_RETRY, "false",
                OVERAGE_PREFIX + "disabled-reason", "fetch_error",
                CONTENT_TYPE, APPLICATION_JSON));
    }

    HttpException limitReached(LimitBreach breach, String deploymentName) {
        String label = breach.window().label();
        String message = switch (breach.kind()) {
            case COST -> "DIAL " + label + " spend limit reached";
            case TOKENS -> "DIAL " + label + " token limit for " + deploymentName + " reached";
            case REQUESTS -> "DIAL " + label + " request limit for " + deploymentName + " reached";
        };
        boolean rolling = breach.window().isRolling();
        message += rolling
                ? "; retry in " + breach.retryAfterSeconds() + " s"
                : " (resets " + formatReset(breach.resetsAtMillis()) + ")";
        return new HttpException(HttpStatus.TOO_MANY_REQUESTS, errorBody("rate_limit_error", message), Map.of(
                "retry-after", String.valueOf(breach.retryAfterSeconds()),
                SHOULD_RETRY, String.valueOf(rolling),
                CONTENT_TYPE, APPLICATION_JSON));
    }

    AnthropicUsageData buildUsage(SpendCaps caps) {
        AnthropicUsageData usage = new AnthropicUsageData();
        usage.setLimits(caps.windows().stream().map(cap -> buildLimit(cap, cap.equals(caps.reported()))).toList());
        SpendCap reported = caps.reported();
        if (reported != null) {
            AnthropicUsageData.ExtraUsage extra = new AnthropicUsageData.ExtraUsage();
            extra.setEnabled(true);
            extra.setMonthlyLimit(toCents(reported.total()));
            extra.setUsedCredits(toCents(reported.used()));
            extra.setUtilization(percent(reported));
            extra.setCurrency("USD");
            usage.setExtraUsage(extra);
        }
        return usage;
    }

    private AnthropicUsageData.UsageLimit buildLimit(SpendCap cap, boolean active) {
        long percent = percent(cap);
        AnthropicUsageData.UsageLimit limit = new AnthropicUsageData.UsageLimit();
        limit.setKind("spend");
        limit.setGroup(periodName(cap.period()));
        limit.setPercent(percent);
        limit.setResetsAt(USAGE_RESET_FORMAT.format(Instant.ofEpochMilli(cap.resetsAtMillis())));
        limit.setSeverity(percent >= 95 ? "critical" : percent >= 75 ? "warning" : "normal");
        limit.setActive(active);
        return limit;
    }

    private long percent(SpendCap cap) {
        return cap.ratio().movePointRight(2).setScale(0, RoundingMode.DOWN).longValue();
    }

    private long toCents(BigDecimal dollars) {
        return dollars.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private String periodName(CalendarPeriod period) {
        return switch (period) {
            case DAY -> "daily";
            case WEEK -> "weekly";
            case MONTH -> "monthly";
        };
    }

    private String formatReset(long epochMillis) {
        return MESSAGE_RESET_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private String errorBody(String type, String message) {
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.put("type", "error");
        body.putObject("error").put("type", type).put("message", message);
        return body.toString();
    }
}
