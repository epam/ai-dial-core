package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.SpendingLimitMode;

import javax.annotation.Nullable;

/**
 * What the pre-call budget check found for one model call of a JWT caller, written to the call's analytics row.
 *
 * @param costObject the cost object the call is charged to; null in {@code off} mode
 * @param outcome    null in {@code off} mode and when a budget counter could not be read
 * @param payer      the budget that pays, set with {@link Outcome#PAID} only
 */
public record BudgetCheck(SpendingLimitMode mode, @Nullable String costObject, @Nullable Outcome outcome, @Nullable BudgetPayer payer) {

    public static final BudgetCheck OFF = new BudgetCheck(SpendingLimitMode.OFF, null, null, null);

    /**
     * The check of a call in {@code shadow} or {@code enforce} mode before a budget counter is read: no outcome yet.
     */
    public static BudgetCheck started(SpendingLimitMode mode, String costObject) {
        return new BudgetCheck(mode, costObject, null, null);
    }

    public BudgetCheck paidBy(BudgetPayer payer) {
        return new BudgetCheck(mode, costObject, Outcome.PAID, payer);
    }

    public BudgetCheck noBudget() {
        return new BudgetCheck(mode, costObject, Outcome.NO_BUDGET, null);
    }

    public BudgetCheck exhausted() {
        return new BudgetCheck(mode, costObject, Outcome.EXHAUSTED, null);
    }

    public enum Outcome {
        PAID("paid"),
        NO_BUDGET("no_budget"),
        EXHAUSTED("exhausted");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String toValue() {
            return value;
        }
    }
}
