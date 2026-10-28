package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.SpendCap;
import com.epam.aidial.core.server.limiter.SpendCaps;
import com.epam.aidial.core.server.limiter.SpendReadException;
import com.epam.aidial.core.storage.http.HttpException;
import io.vertx.core.Future;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

/**
 * The limit check of a route that reports the caller's spend caps to its client: the order of the checks and the
 * fail-closed rule live here, the answers in the route's own format come from {@link Answers}.
 */
@Slf4j
@UtilityClass
public class SpendCapCheck {

    /**
     * A route's answers to the outcomes of the check.
     */
    public interface Answers {

        /**
         * @param displayMessage the limiter's user-facing message, null when the caps were only read
         */
        HttpException spendCapReached(SpendCap cap, @Nullable String displayMessage, long nowMillis);

        HttpException spendUnavailable();

        /**
         * The failure to report when the check itself failed for another reason than an unreadable spend record.
         */
        Throwable limitCheckFailed(Throwable error);

        /**
         * Throws when a limit other than a reached spend cap rejected the call.
         */
        void rejectOtherLimit(RateLimitResult result);
    }

    /**
     * Checks the caller's limits and returns the caps to report. A request whose deployment is not subject to limits
     * (a translated one) is checked and charged by the call the translator makes back to Core, so it only reads the
     * caps: it still rejects a cap that is already reached, and it fails open when the record is unreadable.
     */
    public Future<SpendCaps> verify(Proxy proxy, ProxyContext context, boolean subjectToLimits, Answers answers) {
        String deployment = context.getDeployment().getName();
        if (!subjectToLimits) {
            return proxy.getRateLimiter().readSpendCaps(context)
                    .recover(error -> {
                        log.warn("Failed to read spend caps, sending no limit headers. Deployment: {}", deployment, error);
                        return Future.succeededFuture(SpendCaps.EMPTY);
                    })
                    .map(caps -> {
                        rejectIfSpendCapReached(caps, null, answers);
                        return caps;
                    });
        }
        return proxy.getRateLimiter().limitWithSpendCaps(context, context.getDeployment())
                .recover(error -> {
                    // fail closed only when the spend itself is unknown to a caller it would cap
                    if (error instanceof SpendReadException && proxy.getRateLimiter().hasCostLimit(context)) {
                        log.warn("Failed to read spend, rejecting the request. Deployment: {}", deployment, error);
                        return Future.failedFuture(answers.spendUnavailable());
                    }
                    log.warn("Failed to check limits. Deployment: {}", deployment, error);
                    return Future.failedFuture(answers.limitCheckFailed(error));
                })
                .map(result -> {
                    // a reached spend cap takes precedence over any other limit
                    rejectIfSpendCapReached(result.spendCaps(), result.displayErrorMessage(), answers);
                    answers.rejectOtherLimit(result);
                    return result.spendCaps();
                });
    }

    private void rejectIfSpendCapReached(@Nullable SpendCaps caps, @Nullable String displayMessage, Answers answers) {
        SpendCap reported = caps == null ? null : caps.reported();
        if (reported != null && reported.isExceeded()) {
            throw answers.spendCapReached(reported, displayMessage, System.currentTimeMillis());
        }
    }
}
