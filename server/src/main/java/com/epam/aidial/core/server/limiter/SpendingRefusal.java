package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.storage.http.HttpStatus;

/**
 * Why the budget check refuses a call: the code written to {@code error.code} and to the {@code reason} of
 * {@code dial_budget_rejections_total}, and the status that fits it. 400 is for an indication that is malformed or
 * does not match, 403 for a call no budget can pay, and 429 for a budget or cap that is used up until it resets.
 */
public enum SpendingRefusal {
    INVALID_BUDGET_INDICATION("invalid_budget_indication", HttpStatus.BAD_REQUEST),
    INVALID_COST_OBJECT("invalid_cost_object", HttpStatus.BAD_REQUEST),
    BUDGET_MISMATCH("budget_mismatch", HttpStatus.BAD_REQUEST),
    COST_OBJECT_MISMATCH("cost_object_mismatch", HttpStatus.BAD_REQUEST),
    BUDGET_INDICATION_REQUIRED("budget_indication_required", HttpStatus.BAD_REQUEST),
    NO_BUDGET("no_budget", HttpStatus.FORBIDDEN),
    BUDGET_NOT_APPLICABLE("budget_not_applicable", HttpStatus.FORBIDDEN),
    DEFAULT_BUDGET_NOT_APPLICABLE("default_budget_not_applicable", HttpStatus.FORBIDDEN),
    BUDGET_EXHAUSTED("budget_exhausted", HttpStatus.TOO_MANY_REQUESTS),
    CAP_EXHAUSTED("cap_exhausted", HttpStatus.TOO_MANY_REQUESTS);

    private final String code;
    private final HttpStatus status;

    SpendingRefusal(String code, HttpStatus status) {
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
