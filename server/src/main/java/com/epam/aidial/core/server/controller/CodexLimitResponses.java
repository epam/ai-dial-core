package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.limiter.LimitBreach;
import com.epam.aidial.core.server.limiter.SpendCap;
import com.epam.aidial.core.server.limiter.SpendCaps;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * DIAL spend caps in the shapes Codex CLI reads from a Responses answer: the {@code x-codex-*} headers and the
 * {@code usage_limit_reached} error.
 */
@UtilityClass
class CodexLimitResponses {

    private static final String CODEX_PREFIX = "x-codex-";
    private static final String PRIMARY_PREFIX = CODEX_PREFIX + "primary-";
    private static final String SECONDARY_PREFIX = CODEX_PREFIX + "secondary-";
    private static final String CONTENT_TYPE = "content-type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String STATUS_CODE = String.valueOf(HttpStatus.TOO_MANY_REQUESTS.getCode());
    private static final DateTimeFormatter MESSAGE_RESET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    void stripUpstreamCodexHeaders(MultiMap headers) {
        List<String> upstreamNames = null;
        for (String name : headers.names()) {
            if (name.regionMatches(true, 0, CODEX_PREFIX, 0, CODEX_PREFIX.length())) {
                if (upstreamNames == null) {
                    upstreamNames = new ArrayList<>();
                }
                upstreamNames.add(name);
            }
        }
        if (upstreamNames != null) {
            upstreamNames.forEach(headers::remove);
        }
    }

    void putSpendHeaders(MultiMap headers, SpendCaps caps) {
        SpendCap primary = caps.reported();
        if (primary == null) {
            return;
        }
        putWindow(headers, PRIMARY_PREFIX, primary);
        SpendCap secondary = caps.findSecondary();
        if (secondary != null) {
            putWindow(headers, SECONDARY_PREFIX, secondary);
        }
    }

    /**
     * @param displayMessage the limiter's user-facing message, kept for DIAL clients that show {@code display_message};
     *                       null builds one for this cap alone
     */
    HttpException spendCapReached(SpendCap cap, @Nullable String displayMessage, long nowMillis) {
        long resetAt = cap.resetsAtMillis() / 1000;
        int windowMinutes = cap.period().nominalMinutes();
        String label = LimitBreach.Window.of(cap.period()).label();
        String message = "DIAL " + label + " spend limit reached (resets "
                + MESSAGE_RESET_FORMAT.format(Instant.ofEpochMilli(cap.resetsAtMillis())) + ")";
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.putObject("error")
                .put("type", "usage_limit_reached")
                .put("code", STATUS_CODE)
                .put("message", message)
                .put("display_message", displayMessage == null ? "You've exceeded your " + label + " cost limit" : displayMessage)
                .put("resets_at", resetAt)
                .put("limit_window_minutes", windowMinutes);
        return new HttpException(HttpStatus.TOO_MANY_REQUESTS, body.toString(), Map.of(
                "retry-after", String.valueOf(cap.secondsUntilReset(nowMillis)),
                PRIMARY_PREFIX + "used-percent", "100",
                PRIMARY_PREFIX + "window-minutes", String.valueOf(windowMinutes),
                PRIMARY_PREFIX + "reset-at", String.valueOf(resetAt),
                CONTENT_TYPE, APPLICATION_JSON));
    }

    HttpException spendUnavailable() {
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.putObject("error")
                .put("code", STATUS_CODE)
                .put("message", "DIAL could not read your spend limit; request rejected");
        return new HttpException(HttpStatus.TOO_MANY_REQUESTS, body.toString(), Map.of(CONTENT_TYPE, APPLICATION_JSON));
    }

    private void putWindow(MultiMap headers, String prefix, SpendCap cap) {
        headers.set(prefix + "used-percent", String.valueOf(cap.percent()));
        headers.set(prefix + "window-minutes", String.valueOf(cap.period().nominalMinutes()));
        headers.set(prefix + "reset-at", String.valueOf(cap.resetsAtMillis() / 1000));
    }
}
