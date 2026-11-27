package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.http.HttpHeaders;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * @param isClientRetryDisabled the rejection tells the client not to retry ({@code x-should-retry: false}); Core's
 *                              own retry rules still treat it like any other status
 * @param refusal               why the budget check refused the call, written to {@code error.code}; null for any other
 *                              result, whose {@code error.code} is the status number. Its status is not always this
 *                              one: a route that answers no budget in its own way keeps a 429 inside Core
 */
public record RateLimitResult(
        HttpStatus status, String errorMessage, String displayErrorMessage, long replyAfterSeconds,
        @Nullable LimitBreach breach, @Nullable SpendCaps spendCaps, boolean isClientRetryDisabled,
        @Nullable SpendingRefusal refusal) {
    public static final RateLimitResult SUCCESS = new RateLimitResult(HttpStatus.OK, null, null, -1);

    public RateLimitResult(HttpStatus status, String errorMessage, String displayErrorMessage, long replyAfterSeconds) {
        this(status, errorMessage, displayErrorMessage, replyAfterSeconds, null, null);
    }

    public RateLimitResult(HttpStatus status, String errorMessage, String displayErrorMessage, long replyAfterSeconds,
                           @Nullable LimitBreach breach, @Nullable SpendCaps spendCaps) {
        this(status, errorMessage, displayErrorMessage, replyAfterSeconds, breach, spendCaps, false, null);
    }

    public RateLimitResult withSpendCaps(@Nullable SpendCaps spendCaps) {
        if (spendCaps == null) {
            return this;
        }
        return new RateLimitResult(status, errorMessage, displayErrorMessage, replyAfterSeconds, breach, spendCaps,
                isClientRetryDisabled, refusal);
    }

    /**
     * Whether no budget can pay for the call, or the declared payer does not cover it: a 403 refusal of the budget check.
     * Such a rejection is thrown as a {@link NoBudgetException}, so a route can answer it in its own way.
     */
    public boolean isNoBudget() {
        return refusal != null && refusal.status() == HttpStatus.FORBIDDEN;
    }

    public void throwIfError() {
        if (status != HttpStatus.OK) {
            // Returning an error similar to the Azure format.
            ErrorData rateLimitError = new ErrorData();
            rateLimitError.getError().setCode(refusal != null ? refusal.code() : String.valueOf(status.getCode()));
            rateLimitError.getError().setMessage(errorMessage);
            rateLimitError.getError().setDisplayMessage(displayErrorMessage);

            String body = ProxyUtil.convertToString(rateLimitError);
            Map<String, String> headers = new HashMap<>();
            if (replyAfterSeconds >= 0) {
                headers.put(HttpHeaders.RETRY_AFTER.toString(), Long.toString(replyAfterSeconds));
            }
            if (isClientRetryDisabled) {
                headers.put("x-should-retry", "false");
            }
            if (isNoBudget()) {
                throw new NoBudgetException(errorMessage, status, body, headers);
            }
            throw new HttpException(status, body, headers);
        }
    }
}
