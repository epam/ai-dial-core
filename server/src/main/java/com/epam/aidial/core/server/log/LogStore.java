package com.epam.aidial.core.server.log;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.budget.BudgetCheck;

public interface LogStore {

    void save(AnalyticsLogContext logContext);

    /** From {@code analytics.errorRows}. */
    default boolean isErrorRowsEnabled() {
        return false;
    }

    /** Saves the row of a pass that ended in an error, when {@code analytics.errorRows} is on. */
    default void saveErrorRow(ProxyContext context) {
        saveErrorRow(context, true);
    }

    private void saveErrorRow(ProxyContext context, boolean withBodies) {
        // only POST requests write rows, which GfLogStore.save checks too, but only after the row is built
        if (isErrorRowsEnabled() && "POST".equals(context.getRequest().method().name())) {
            AnalyticsLogContext row = AnalyticsLogContext.fromError(context);
            save(withBodies ? row : row.withoutBodies());
        }
    }

    /**
     * Saves the row of an error answer that Core gave itself, when {@code analytics.errorRows} is on and the status of the response is
     * outside 200-299: the answers that {@code ProxyContext.respond} logs at WARN. Call it right after {@code respond(...)}. The row
     * keeps its bodies only for a 400 or a 500, the answers the body may have caused, and not when spending limits refused the call.
     */
    default void saveErrorResponse(ProxyContext context) {
        int statusCode = context.getResponse().getStatusCode();
        if (statusCode < 200 || statusCode >= 300) {
            // the refusal is a 400 on /openai/v1/responses, whose message Codex CLI shows, and the body did not cause it
            BudgetCheck budgetCheck = context.getBudgetCheck();
            boolean refusedByBudget = budgetCheck != null && budgetCheck.refuses();
            saveErrorRow(context, (statusCode == 400 || statusCode == 500) && !refusedByBudget);
        }
    }
}
