package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.SpendingLimits;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.SpendingRefusal;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.service.policy.PolicyService;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.micrometer.core.instrument.Metrics;
import io.vertx.core.MultiMap;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the budget headers of a JWT caller's root request to a deployment: {@code X-DIAL-BUDGET}, the budget instance
 * that pays, and {@code X-DIAL-COST-OBJECT}, the cost object that scopes the call where the identity provider has no
 * {@code costObjectPath}. The checks need no budget counter, so they refuse in every mode.
 */
@Slf4j
@UtilityClass
public class BudgetHeaders {

    /**
     * Refuses a malformed or mismatched declaration with 400, and stores the indication and the cost object the
     * headers name on the context, for the budget check and the per-request key. A call made with a per-request key
     * and an API key call keep what they have: their headers are ignored.
     */
    public void verifyRootCall(ProxyContext context) {
        MultiMap headers = context.getRequest().headers();
        String indication = headers.get(Proxy.HEADER_DIAL_BUDGET);
        String costObjectHeader = headers.get(Proxy.HEADER_DIAL_COST_OBJECT);
        ExtractedClaims claims = context.getExtractedClaims();
        if (claims == null || !context.isOriginalRequest()) {
            if (indication != null || costObjectHeader != null) {
                log.debug("Budget headers ignored on a call with a per-request key or an API key");
            }
            return;
        }
        SpendingLimits settings = context.getConfig().getSpendingLimits();
        if (costObjectHeader != null && !PolicyService.isValidElement(costObjectHeader)) {
            refuse(settings, SpendingRefusal.INVALID_COST_OBJECT, Proxy.HEADER_DIAL_COST_OBJECT + " must be one cost object name");
        }
        if (indication != null && !isValidIndication(indication)) {
            refuse(settings, SpendingRefusal.INVALID_BUDGET_INDICATION, Proxy.HEADER_DIAL_BUDGET + " must be <costObject>/<id>");
        }
        if (indication == null && settings.isBudgetMandatory()) {
            refuse(settings, SpendingRefusal.BUDGET_INDICATION_REQUIRED, "Indicate the budget that pays with the "
                    + Proxy.HEADER_DIAL_BUDGET + " header");
        }
        String indicatedCostObject = indication == null ? null : indication.substring(0, indication.indexOf('/'));
        if (claims.costObjectPathSet()) {
            if (costObjectHeader != null) {
                log.debug("{} ignored: the identity provider sets costObjectPath", Proxy.HEADER_DIAL_COST_OBJECT);
            }
            CostObject effective = CostObject.resolve(claims.project(), null, () -> settings);
            if (indicatedCostObject != null && !indicatedCostObject.equals(effective.id())) {
                refuse(settings, SpendingRefusal.COST_OBJECT_MISMATCH, "Budget " + indication + " is on cost object "
                        + indicatedCostObject + ", but this call is charged to " + effective.id() + " (" + effective.source().toValue() + ")");
            }
        } else {
            if (indication != null && costObjectHeader != null) {
                log.debug("{} ignored: the call indicates a budget", Proxy.HEADER_DIAL_COST_OBJECT);
            }
            String scope = indicatedCostObject != null ? indicatedCostObject : costObjectHeader;
            // the configured default attests as it always has, so naming it changes nothing
            if (scope != null && !scope.equals(settings.getDefaultCostObject())) {
                context.setHeaderCostObject(scope);
            }
        }
        context.setBudgetIndication(indication);
    }

    private static boolean isValidIndication(String indication) {
        int slash = indication.indexOf('/');
        return slash > 0 && slash == indication.lastIndexOf('/')
                && PolicyService.isValidElement(indication.substring(0, slash))
                && PolicyService.isValidElement(indication.substring(slash + 1));
    }

    private static void refuse(SpendingLimits settings, SpendingRefusal refusal, String message) {
        Metrics.counter("dial_budget_rejections_total", "reason", refusal.code(), "mode", settings.getMode().toValue()).increment();
        new RateLimitResult(HttpStatus.BAD_REQUEST, message, message, -1, null, null, false, refusal).throwIfError();
    }
}
