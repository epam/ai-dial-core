package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.config.RoleBasedEntity;
import com.epam.aidial.core.config.SpendingLimitMode;
import com.epam.aidial.core.config.SpendingLimits;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.config.policy.PolicyQuantity;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.BudgetStats;
import com.epam.aidial.core.server.data.CostItemLimitStats;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.micrometer.core.instrument.Metrics;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Checks a call against the budgets that can pay for it and remembers the one that does.
 */
@Slf4j
@RequiredArgsConstructor
public class BudgetEnforcer {

    private static final String NO_BUDGET = "no_budget";
    private static final String BUDGET_EXHAUSTED = "budget_exhausted";

    private final BudgetCounterStore store;

    /**
     * The project of the root caller, or the configured default when the call carries none.
     */
    public static String resolveCostObject(ProxyContext context, SpendingLimits settings) {
        if (context.getUserProject() != null) {
            return context.getUserProject();
        }
        if (context.getKey() != null && context.getKey().getProject() != null) {
            return context.getKey().getProject();
        }
        return settings.getDefaultCostObject();
    }

    /**
     * Whether budgets take part in the call. API key callers, calls to anything but a model and the {@code off}
     * mode stay on the legacy cost limit.
     */
    public static boolean isApplicable(ProxyContext context, RoleBasedEntity entity) {
        SpendingLimits settings = context.getConfig().getSpendingLimits();
        return settings.getMode() != SpendingLimitMode.OFF
                && context.getExtractedClaims() != null
                && entity instanceof Model;
    }

    public static boolean isEnforced(ProxyContext context, RoleBasedEntity entity) {
        return isApplicable(context, entity) && context.getConfig().getSpendingLimits().getMode() == SpendingLimitMode.ENFORCE;
    }

    /**
     * Picks the first candidate with every configured window below its limit and stores it on the context.
     * The result is a rejection only in {@code enforce} mode; in {@code shadow} it is logged and metered.
     */
    public Future<RateLimitResult> check(ProxyContext context, Model model, long timestamp, RateLimitSchedule schedule) {
        SpendingLimits settings = context.getConfig().getSpendingLimits();
        ExtractedClaims claims = context.getExtractedClaims();
        String costObject = resolveCostObject(context, settings);
        List<BudgetInstance> candidates = EffectiveBudgets
                .resolve(context.getConfig().getCostObjectIndex(), costObject, claims, model.getName(), Instant.ofEpochMilli(timestamp))
                .stream()
                .filter(instance -> instance.policy().getQuantity() == PolicyQuantity.COST)
                .toList();
        if (candidates.isEmpty()) {
            return Future.succeededFuture(reject(settings, NO_BUDGET, claims.userId(), costObject, model, new RateLimitResult(
                    HttpStatus.TOO_MANY_REQUESTS, "No budget covers " + model.getName() + " for cost object " + costObject,
                    "You have no budget for this model", -1)));
        }
        return pick(context, candidates, 0, costObject, claims.userId(), timestamp, schedule, Long.MAX_VALUE)
                .map(outcome -> {
                    if (outcome.payer != null) {
                        context.setBudgetPayer(outcome.payer);
                        return RateLimitResult.SUCCESS;
                    }
                    // whole seconds rounded up, so a client never retries before the window has reset
                    long retryAfter = Math.max(1, Math.ceilDiv(outcome.earliestResetAt - timestamp, 1000L));
                    return reject(settings, BUDGET_EXHAUSTED, claims.userId(), costObject, model, new RateLimitResult(
                            HttpStatus.TOO_MANY_REQUESTS, "Budget exhausted for cost object " + costObject,
                            "You've exceeded your budget", retryAfter));
                });
    }

    /**
     * The budgets to report to the caller, every matching one, with the spend so far. Blocks, so it is for a
     * caller that is already on the task executor.
     */
    public List<BudgetStats> collectStats(ProxyContext context, long timestamp, RateLimitSchedule schedule) {
        ExtractedClaims claims = context.getExtractedClaims();
        if (context.getConfig().getSpendingLimits().getMode() == SpendingLimitMode.OFF || claims == null) {
            return List.of();
        }
        List<BudgetStats> stats = new ArrayList<>();
        for (BudgetInstance instance : EffectiveBudgets
                .resolveAll(context.getConfig().getCostObjectIndex(), claims, Instant.ofEpochMilli(timestamp))) {
            String costObject = instance.id().substring(0, instance.id().indexOf('/'));
            BudgetUsage usage = store.loadBlocking(costObject, instance, claims.userId(), timestamp, schedule);
            BudgetStats budget = new BudgetStats();
            budget.setCostObject(costObject);
            budget.setPolicy(instance.policyPath());
            budget.setPriority(instance.policy().getPriority());
            budget.setDay(toStats(instance.policy().getCost().getDay(), usage.day(), usage.dayResetsAt(), schedule));
            budget.setMonth(toStats(instance.policy().getCost().getMonth(), usage.month(), usage.monthResetsAt(), schedule));
            stats.add(budget);
        }
        return stats;
    }

    private static CostItemLimitStats toStats(@Nullable BigDecimal limit, BigDecimal used, long resetsAt, RateLimitSchedule schedule) {
        CostItemLimitStats stats = new CostItemLimitStats();
        stats.setTotal(limit != null ? limit : BigDecimal.valueOf(Long.MAX_VALUE));
        stats.setUsed(used);
        stats.setResetsAt(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.ofEpochMilli(resetsAt).atZone(ZoneId.of(schedule.getTimezone()))));
        return stats;
    }

    /**
     * Adds the cost to the payer's row for the model, in full even when it takes the budget over its limit.
     */
    public Future<Void> charge(BudgetPayer payer, String model, BigDecimal cost, long timestamp, RateLimitSchedule schedule) {
        return store.charge(payer.costObject(), payer.instance(), payer.userId(), model, cost, timestamp, schedule);
    }

    private Future<Outcome> pick(ProxyContext context, List<BudgetInstance> candidates, int index, String costObject, String userId,
                                 long timestamp, RateLimitSchedule schedule, long earliestResetAt) {
        if (index == candidates.size()) {
            return Future.succeededFuture(new Outcome(null, earliestResetAt));
        }
        BudgetInstance instance = candidates.get(index);
        return store.load(costObject, instance, userId, timestamp, schedule).compose(usage -> {
            boolean dayDry = isDry(usage.day(), instance.policy().getCost().getDay());
            boolean monthDry = isDry(usage.month(), instance.policy().getCost().getMonth());
            if (!dayDry && !monthDry) {
                return Future.succeededFuture(new Outcome(new BudgetPayer(costObject, instance, userId), 0));
            }
            // the budget pays again once every window that is dry has reset
            long resetAt = Math.max(dayDry ? usage.dayResetsAt() : 0, monthDry ? usage.monthResetsAt() : 0);
            return pick(context, candidates, index + 1, costObject, userId, timestamp, schedule, Math.min(earliestResetAt, resetAt));
        });
    }

    private static boolean isDry(BigDecimal spent, @Nullable BigDecimal limit) {
        return limit != null && spent.compareTo(limit) >= 0;
    }

    private static RateLimitResult reject(SpendingLimits settings, String reason, String userId, String costObject, Model model,
                                      RateLimitResult rejection) {
        Metrics.counter("dial_budget_rejections_total", "reason", reason, "mode", settings.getMode().toValue()).increment();
        if (settings.getMode() == SpendingLimitMode.ENFORCE) {
            return rejection;
        }
        log.info("Shadow budget check would reject: reason={}, user={}, costObject={}, model={}",
                reason, userId, costObject, model.getName());
        return RateLimitResult.SUCCESS;
    }

    private record Outcome(@Nullable BudgetPayer payer, long earliestResetAt) {
    }
}
