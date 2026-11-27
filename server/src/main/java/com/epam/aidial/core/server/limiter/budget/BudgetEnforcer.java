package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.config.RoleBasedEntity;
import com.epam.aidial.core.config.SpendingLimitMode;
import com.epam.aidial.core.config.SpendingLimits;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.config.policy.PolicyAmounts;
import com.epam.aidial.core.config.policy.PolicyQuantity;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.BudgetStats;
import com.epam.aidial.core.server.data.CostItemLimitStats;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.SpendCap;
import com.epam.aidial.core.server.limiter.SpendCaps;
import com.epam.aidial.core.server.limiter.SpendReadException;
import com.epam.aidial.core.server.limiter.SpendingRefusal;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
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

    private final BudgetCounterStore store;
    private final DefaultBudgetService defaultBudgets;
    private final AsyncTaskExecutor taskExecutor;

    /**
     * The effective cost object of the root caller: the one its budget headers named, its claim, or the configured
     * default when the call carries none.
     */
    public static String resolveCostObject(ProxyContext context) {
        return CostObject.resolve(context.getHeaderCostObject(), context.getUserProject(), context.getKey(),
                () -> context.getConfig().getSpendingLimits()).id();
    }

    /**
     * Whether budgets check the call when they are on: a JWT caller's call to a model. A JWT caller's call to anything
     * else is not checked against budgets: the models it calls are.
     */
    public static boolean isApplicable(ProxyContext context, RoleBasedEntity entity) {
        return context.getExtractedClaims() != null && entity instanceof Model;
    }

    public static boolean isEnforced(ProxyContext context, RoleBasedEntity entity) {
        return isRoleCostLimitSkipped(context) && isApplicable(context, entity);
    }

    /**
     * Whether the legacy role cost limit is off for the call: in {@code enforce} mode, for a JWT caller, whatever the
     * deployment. API key callers keep it in every mode, as no budget reaches them.
     */
    public static boolean isRoleCostLimitSkipped(ProxyContext context) {
        return context.getConfig().getSpendingLimits().getMode() == SpendingLimitMode.ENFORCE && context.getExtractedClaims() != null;
    }

    /**
     * The check of a call budgets cover, before any budget counter is read: the mode only in {@code off}, the mode
     * and the cost object without an outcome otherwise.
     */
    public static BudgetCheck startCheck(ProxyContext context) {
        SpendingLimits settings = context.getConfig().getSpendingLimits();
        return settings.getMode() == SpendingLimitMode.OFF
                ? BudgetCheck.OFF : BudgetCheck.started(settings.getMode(), resolveCostObject(context));
    }

    /**
     * Picks the first candidate with every configured window below its limit and stores it on the context.
     * The result is a rejection only in {@code enforce} mode; in {@code shadow} it is logged and metered.
     */
    public Future<RateLimitResult> check(ProxyContext context, Model model, long timestamp, RateLimitSchedule schedule) {
        return checkBudgets(context, model, timestamp, schedule, false);
    }

    /**
     * The {@code enforce} check of a route that reports spend caps to its client. It picks and stores the payer as
     * {@link #check} does. The result carries the caps of the payer, whichever budget the ladder resolved. When the
     * payer, or every budget of the walk, is used up, it carries the cap of the one that can pay again first. A failed
     * load fails with {@link SpendReadException} only when a candidate caps the caller.
     */
    public Future<RateLimitResult> checkWithSpendCaps(ProxyContext context, Model model, long timestamp, RateLimitSchedule schedule) {
        return checkBudgets(context, model, timestamp, schedule, true);
    }

    /**
     * The resolution ladder: the indicated budget, else the caller's default budget when it is on the effective cost
     * object, else the priority walk when {@code autoBudgetSelection} allows it. A declared payer is the only candidate.
     */
    private Future<RateLimitResult> checkBudgets(ProxyContext context, Model model, long timestamp, RateLimitSchedule schedule,
                                                 boolean isWithSpendCaps) {
        SpendingLimits settings = context.getConfig().getSpendingLimits();
        ExtractedClaims claims = context.getExtractedClaims();
        String costObject = resolveCostObject(context);
        // without an outcome until the counters are read, so a failed read leaves the row without one
        BudgetCheck started = BudgetCheck.started(settings.getMode(), costObject);
        context.setBudgetCheck(started);
        Future<Candidates> resolved = context.getBudgetIndication() != null
                ? Future.succeededFuture(resolveIndicated(context, costObject, timestamp))
                : taskExecutor.submit(() -> defaultBudgets.findDefault(context))
                        .map(defaultBudget -> resolveUnindicated(context, model, costObject, defaultBudget, timestamp));
        return resolved.compose(candidates -> {
            if (candidates.refusal() != null) {
                context.setBudgetCheck(started.noBudget());
                return Future.succeededFuture(rejectForbidden(settings, candidates.refusal(), candidates.message(), claims, costObject, model,
                        isWithSpendCaps));
            }
            return pickAndReport(context, model, candidates.budgets(), costObject, started, timestamp, schedule, isWithSpendCaps);
        });
    }

    private Future<RateLimitResult> pickAndReport(ProxyContext context, Model model, List<BudgetInstance> candidates, String costObject,
                                                  BudgetCheck started, long timestamp, RateLimitSchedule schedule, boolean isWithSpendCaps) {
        SpendingLimits settings = context.getConfig().getSpendingLimits();
        ExtractedClaims claims = context.getExtractedClaims();
        Future<Outcome> picked = pickPayer(candidates, costObject, claims.userId(), timestamp, schedule);
        if (isWithSpendCaps) {
            picked = picked.recover(error -> Future.failedFuture(candidates.stream().anyMatch(BudgetEnforcer::hasLimitedWindow)
                    ? new SpendReadException(error) : error));
        }
        return picked.map(outcome -> {
            if (outcome.payer != null) {
                context.setBudgetCheck(started.paidBy(outcome.payer));
                if (!isWithSpendCaps) {
                    return RateLimitResult.SUCCESS;
                }
                return RateLimitResult.SUCCESS.withSpendCaps(toSpendCaps(outcome.payer.instance(), outcome.payerUsage));
            }
            context.setBudgetCheck(started.exhausted());
            // with spend caps there is no display message: a client builds it from the cap's period
            RateLimitResult exhausted = buildExhaustedResult(costObject, isWithSpendCaps ? null : "You've exceeded your budget",
                    outcome, timestamp);
            if (isWithSpendCaps) {
                exhausted = exhausted.withSpendCaps(outcome.earliestCap == null
                        ? SpendCaps.EMPTY : new SpendCaps(List.of(outcome.earliestCap), outcome.earliestCap));
            }
            return reject(settings, SpendingRefusal.BUDGET_EXHAUSTED, claims.userId(), costObject, model, exhausted);
        });
    }

    /**
     * The indicated budget is the payer, exclusively: one the caller does not hold, or one that does not cover the call,
     * refuses it. Its cost object is the effective one: the root request refused any other with 400.
     */
    private static Candidates resolveIndicated(ProxyContext context, String costObject, long timestamp) {
        String indication = context.getBudgetIndication();
        BudgetInstance held = EffectiveBudgets.findHeld(context.getConfig().getCostObjectIndex(), costObject, indication,
                context.getExtractedClaims(), Instant.ofEpochMilli(timestamp), context.getHeaderCostObject() == null);
        if (held == null) {
            // the same whether it does not exist, is someone else's or has expired, so it tells nothing about other budgets
            return Candidates.refused(SpendingRefusal.NO_BUDGET, "Budget " + indication + " is not available to you");
        }
        List<String> chain = resolveCoverageChain(context);
        if (!EffectiveBudgets.isCovered(held, chain)) {
            return Candidates.refused(SpendingRefusal.BUDGET_NOT_APPLICABLE, "Budget " + indication + " does not cover " + chain.get(0));
        }
        return new Candidates(List.of(held), null, null);
    }

    /**
     * @param defaultBudget the caller's default budget while they hold it in any cost object, null when none is set
     */
    private static Candidates resolveUnindicated(ProxyContext context, Model model, String costObject, @Nullable BudgetInstance defaultBudget,
                                                 long timestamp) {
        boolean isAttested = context.getHeaderCostObject() == null;
        Instant now = Instant.ofEpochMilli(timestamp);
        List<String> chain = resolveCoverageChain(context);
        // a default on another cost object is not in effect: a standing preference, not a declaration of this call
        if (defaultBudget != null && defaultBudget.id().startsWith(costObject + "/")) {
            BudgetInstance held = EffectiveBudgets.findHeld(context.getConfig().getCostObjectIndex(), costObject, defaultBudget.id(),
                    context.getExtractedClaims(), now, isAttested);
            if (held != null) {
                if (!EffectiveBudgets.isCovered(held, chain)) {
                    return Candidates.refused(SpendingRefusal.DEFAULT_BUDGET_NOT_APPLICABLE, "Your default budget " + held.id()
                            + " does not cover " + chain.get(0) + ". Indicate a budget with the " + Proxy.HEADER_DIAL_BUDGET + " header.");
                }
                return new Candidates(List.of(held), null, null);
            }
        }
        if (!context.getConfig().getSpendingLimits().isAutoBudgetSelection()) {
            return Candidates.refused(SpendingRefusal.NO_BUDGET, "No budget is indicated for " + model.getName() + ". Indicate a budget with the "
                    + Proxy.HEADER_DIAL_BUDGET + " header or set a default budget.");
        }
        List<BudgetInstance> candidates = resolveCandidates(context, costObject, chain, now, isAttested);
        if (candidates.isEmpty()) {
            // a user reads the message as it is, in Codex CLI and Claude Code; it keeps the model and the cost object for the admin
            return Candidates.refused(SpendingRefusal.NO_BUDGET, "No DIAL spend limit is assigned for " + model.getName() + " in project "
                    + costObject + ". Ask your DIAL admin to assign one.");
        }
        return new Candidates(candidates, null, null);
    }

    /**
     * The budgets that can pay, or why none can.
     *
     * @param refusal null when {@code budgets} has the candidates
     */
    private record Candidates(List<BudgetInstance> budgets, @Nullable SpendingRefusal refusal, @Nullable String message) {
        static Candidates refused(SpendingRefusal refusal, String message) {
            return new Candidates(List.of(), refusal, message);
        }
    }

    /**
     * Whether the read-only spend caps come from budgets: {@code enforce} mode, a JWT caller, and either no
     * deployment (the usage endpoint) or a model.
     */
    public static boolean isReadFromBudgets(ProxyContext context) {
        Deployment deployment = context.getDeployment();
        return context.getConfig().getSpendingLimits().getMode() == SpendingLimitMode.ENFORCE
                && context.getExtractedClaims() != null
                && (deployment == null || deployment instanceof Model);
    }

    /**
     * The caller's spend caps from their one budget in their cost object, read without checking or charging
     * anything. None when they have several budgets or none, and none without a read when the one budget has no
     * window small enough to cap.
     */
    public Future<SpendCaps> readSpendCaps(ProxyContext context, long timestamp, RateLimitSchedule schedule) {
        ExtractedClaims claims = context.getExtractedClaims();
        String costObject = resolveCostObject(context);
        List<BudgetInstance> budgets = EffectiveBudgets.resolveInCostObject(context.getConfig().getCostObjectIndex(), costObject,
                claims, Instant.ofEpochMilli(timestamp));
        if (budgets.size() != 1 || !hasLimitedWindow(budgets.get(0))) {
            return Future.succeededFuture(SpendCaps.EMPTY);
        }
        BudgetInstance budget = budgets.get(0);
        return store.load(costObject, budget, claims.userId(), timestamp, schedule).map(usage -> toSpendCaps(budget, usage));
    }

    private static List<BudgetInstance> resolveCandidates(ProxyContext context, String costObject, List<String> chain, Instant now,
                                                          boolean isAttested) {
        return EffectiveBudgets
                .resolve(context.getConfig().getCostObjectIndex(), costObject, context.getExtractedClaims(), chain, now, isAttested)
                .stream()
                .filter(instance -> instance.policy().getQuantity() == PolicyQuantity.COST)
                .toList();
    }

    /**
     * The names a budget can cover the call by: the model and, read back from the execution path, the names before it
     * up to the first application, interceptor or toolset, each of which starts a call of its own.
     */
    static List<String> resolveCoverageChain(ProxyContext context) {
        List<String> path = ApiKeyData.pathOf(context);
        Config config = context.getConfig();
        int head = path.size() - 1;
        while (head > 0 && !isChainBreak(config, path.get(head - 1))) {
            head--;
        }
        return List.copyOf(path.subList(head, path.size()));
    }

    private static boolean isChainBreak(Config config, String name) {
        return config.getApplications().containsKey(name) || config.getInterceptors().containsKey(name)
                || config.getToolsets().containsKey(name)
                || name.startsWith(ResourceTypes.APPLICATION.group() + "/") || name.startsWith(ResourceTypes.TOOL_SET.group() + "/");
    }

    /**
     * A 403 refusal: no budget can pay, or the declared payer does not cover the call. The message names only the
     * caller's own model, budget and cost object, so it tells nothing about other people's budgets.
     *
     * @param isWithSpendCaps the routes that report spend caps tell their client not to retry: only an admin adding
     *                        a budget, or the client indicating another, can make the call pass. They also answer the
     *                        refusal in their own way
     */
    private static RateLimitResult rejectForbidden(SpendingLimits settings, SpendingRefusal refusal, String message, ExtractedClaims claims,
                                                   String costObject, Model model, boolean isWithSpendCaps) {
        // the Messages and Responses routes answer their client in their own way and keep a 429 inside Core, which
        // an alias pass and a router fall back on; every other route answers the refusal's own status
        HttpStatus status = isWithSpendCaps ? HttpStatus.TOO_MANY_REQUESTS : refusal.status();
        String displayMessage = refusal == SpendingRefusal.NO_BUDGET ? "You have no budget for this model" : message;
        return reject(settings, refusal, claims.userId(), costObject, model, new RateLimitResult(
                status, message, displayMessage, -1, null, null, isWithSpendCaps, refusal));
    }

    private static RateLimitResult buildExhaustedResult(String costObject, @Nullable String displayMessage, Outcome outcome, long timestamp) {
        // whole seconds rounded up, so a client never retries before the window has reset
        long retryAfter = Math.max(1, Math.ceilDiv(outcome.earliestResetAt - timestamp, 1000L));
        SpendingRefusal refusal = SpendingRefusal.BUDGET_EXHAUSTED;
        return new RateLimitResult(refusal.status(), "Budget exhausted for cost object " + costObject,
                displayMessage, retryAfter, null, null, false, refusal);
    }

    /**
     * The budgets to report to the caller, every matching one, with the spend so far, and the cost object the
     * caller's model calls are charged to. Blocks, so it is for a caller that is already on the task executor.
     */
    public BudgetReport collectStats(ProxyContext context, long timestamp, RateLimitSchedule schedule) {
        ExtractedClaims claims = context.getExtractedClaims();
        SpendingLimits settings = context.getConfig().getSpendingLimits();
        if (settings.getMode() == SpendingLimitMode.OFF || claims == null) {
            return new BudgetReport(null, List.of());
        }
        List<BudgetStats> stats = new ArrayList<>();
        for (BudgetInstance instance : EffectiveBudgets
                .resolveAll(context.getConfig().getCostObjectIndex(), claims, Instant.ofEpochMilli(timestamp))) {
            String costObject = instance.id().substring(0, instance.id().indexOf('/'));
            BudgetUsage usage = store.loadBlocking(costObject, instance, claims.userId(), timestamp, schedule);
            BudgetStats budget = new BudgetStats();
            budget.setCostObject(costObject);
            budget.setPolicy(instance.policyPath());
            String displayName = instance.policy().getDisplayName();
            budget.setDisplayName(displayName == null || displayName.isBlank() ? instance.policyPath() : displayName);
            budget.setPriority(instance.policy().getPriority());
            budget.setObject(instance.policy().getObject());
            budget.setDay(toStats(instance.policy().getCost().getDay(), usage.day(), usage.dayResetsAt(), schedule));
            budget.setMonth(toStats(instance.policy().getCost().getMonth(), usage.month(), usage.monthResetsAt(), schedule));
            stats.add(budget);
        }
        return new BudgetReport(resolveCostObject(context), stats);
    }

    /**
     * @param costObject the caller's cost object, {@code null} when budgets do not apply to the caller
     */
    public record BudgetReport(@Nullable String costObject, List<BudgetStats> budgets) {
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

    /**
     * Loads the candidates in order until one can pay, so a later one is not read once an earlier one pays.
     */
    private Future<Outcome> pickPayer(List<BudgetInstance> candidates, String costObject, String userId,
                                      long timestamp, RateLimitSchedule schedule) {
        Future<Outcome> result = Future.succeededFuture(new Outcome(null, null, Long.MAX_VALUE, null));
        for (BudgetInstance instance : candidates) {
            result = result.compose(previous -> previous.payer != null
                    ? Future.succeededFuture(previous)
                    : store.load(costObject, instance, userId, timestamp, schedule)
                            .map(usage -> next(previous, instance, usage, costObject, userId)));
        }
        return result;
    }

    /**
     * The outcome after one more candidate. {@code previous.earliestCap} is the reported cap of the dry candidate
     * that pays again first so far; a later candidate takes its place only when it resets strictly earlier, so a
     * tie keeps the earlier one.
     */
    private static Outcome next(Outcome previous, BudgetInstance instance, BudgetUsage usage, String costObject, String userId) {
        boolean dayDry = isDry(usage.day(), instance.policy().getCost().getDay());
        boolean monthDry = isDry(usage.month(), instance.policy().getCost().getMonth());
        if (!dayDry && !monthDry) {
            return new Outcome(new BudgetPayer(costObject, instance, userId), usage, 0, null);
        }
        // the budget pays again once every window that is dry has reset, which is the cap SpendCaps reports for it
        long resetAt = Math.max(dayDry ? usage.dayResetsAt() : 0, monthDry ? usage.monthResetsAt() : 0);
        if (resetAt >= previous.earliestResetAt) {
            return previous;
        }
        return new Outcome(null, null, resetAt, toSpendCaps(instance, usage).reported());
    }

    /**
     * The budget's day and month amounts as spend caps; a window the policy does not set is left out.
     */
    static SpendCaps toSpendCaps(BudgetInstance instance, BudgetUsage usage) {
        PolicyAmounts cost = instance.policy().getCost();
        List<SpendCap> windows = new ArrayList<>(2);
        if (cost.getDay() != null) {
            windows.add(new SpendCap(CalendarPeriod.DAY, usage.day(), cost.getDay(), usage.dayResetsAt()));
        }
        if (cost.getMonth() != null) {
            windows.add(new SpendCap(CalendarPeriod.MONTH, usage.month(), cost.getMonth(), usage.monthResetsAt()));
        }
        return SpendCaps.of(windows);
    }

    /**
     * Whether the budget sets a window small enough to cap, by the same 2^53 rule as the role spend caps.
     */
    static boolean hasLimitedWindow(BudgetInstance instance) {
        PolicyAmounts cost = instance.policy().getCost();
        return (cost.getDay() != null && SpendCaps.isLimited(cost.getDay()))
                || (cost.getMonth() != null && SpendCaps.isLimited(cost.getMonth()));
    }

    private static boolean isDry(BigDecimal spent, @Nullable BigDecimal limit) {
        return limit != null && spent.compareTo(limit) >= 0;
    }

    private static RateLimitResult reject(SpendingLimits settings, SpendingRefusal refusal, String userId, String costObject, Model model,
                                          RateLimitResult rejection) {
        Metrics.counter("dial_budget_rejections_total", "reason", refusal.code(), "mode", settings.getMode().toValue()).increment();
        if (settings.getMode() == SpendingLimitMode.ENFORCE) {
            return rejection;
        }
        log.info("Shadow budget check would reject: reason={}, user={}, costObject={}, model={}",
                refusal.code(), userId, costObject, model.getName());
        return RateLimitResult.SUCCESS;
    }

    /**
     * @param payerUsage  the payer's spend as loaded, null when no candidate pays
     * @param earliestCap the cap of the dry candidate that pays again first, null when a candidate pays or when the
     *                    dry candidate only has a window of 0, which is enforced but never reported
     */
    private record Outcome(@Nullable BudgetPayer payer, @Nullable BudgetUsage payerUsage, long earliestResetAt,
                           @Nullable SpendCap earliestCap) {
    }
}
