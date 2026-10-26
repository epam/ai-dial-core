package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.http.HttpHeaders;

import java.util.Map;
import javax.annotation.Nullable;

public record RateLimitResult(
        HttpStatus status, String errorMessage, String displayErrorMessage, long replyAfterSeconds,
        @Nullable LimitBreach breach, @Nullable SpendCaps spendCaps) {
    public static final RateLimitResult SUCCESS = new RateLimitResult(HttpStatus.OK, null, null, -1);

    public RateLimitResult(HttpStatus status, String errorMessage, String displayErrorMessage, long replyAfterSeconds) {
        this(status, errorMessage, displayErrorMessage, replyAfterSeconds, null, null);
    }

    public RateLimitResult withSpendCaps(@Nullable SpendCaps spendCaps) {
        if (spendCaps == null) {
            return this;
        }
        return new RateLimitResult(status, errorMessage, displayErrorMessage, replyAfterSeconds, breach, spendCaps);
    }

    public void throwIfError() {
        if (status != HttpStatus.OK) {
            // Returning an error similar to the Azure format.
            ErrorData rateLimitError = new ErrorData();
            rateLimitError.getError().setCode(String.valueOf(status.getCode()));
            rateLimitError.getError().setMessage(errorMessage);
            rateLimitError.getError().setDisplayMessage(displayErrorMessage);

            String errorMessage = ProxyUtil.convertToString(rateLimitError);
            if (replyAfterSeconds >= 0) {
                Map<String, String> headers = Map.of(HttpHeaders.RETRY_AFTER.toString(), Long.toString(replyAfterSeconds));
                throw new HttpException(status, errorMessage, headers);
            }

            throw new HttpException(status, errorMessage);
        }
    }
}
