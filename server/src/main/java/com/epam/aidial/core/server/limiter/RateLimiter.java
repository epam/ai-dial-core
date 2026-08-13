package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.CostLimit;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.RoleBasedEntity;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.CostItemLimitStats;
import com.epam.aidial.core.server.data.ItemLimitStats;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.data.UserLimitStats;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ModelCostCalculator;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

@Slf4j
@RequiredArgsConstructor
public class RateLimiter {

    private static final Limit DEFAULT_LIMIT = new Limit();
    private static final CostLimit DEFAULT_COST_LIMIT = new CostLimit();
    private static final String DEFAULT_USER_ROLE = "default";
    private static final int LIST_PAGE_SIZE = 1000;

    private final AsyncTaskExecutor taskExecutor;

    private final ResourceService resourceService;

    public Future<Void> increase(
            RoleBasedEntity roleBasedEntity, String bucket, TokenUsage usage, Buffer requestBody, Buffer responseBody) {
        try {
            // skip checking limits if redis is not available
            if (resourceService == null) {
                return Future.succeededFuture();
            }

            BigDecimal cost = ModelCostCalculator.calculate(roleBasedEntity, usage, requestBody, responseBody);
            Future<Void> costFuture;
            if (cost != null && cost.compareTo(BigDecimal.ZERO) > 0) {
                if (usage != null) {
                    usage.setCost(cost);
                    usage.setAggCost(cost);
                }

                ResourceDescriptor costResourceDescription = getResourceDescription(bucket, getPathToCosts());
                // the global document is what enforces; the deployment-scoped one only attributes the same
                // figure, so that a bulk report can break spend down without re-deriving it from stored
                // tokens - which is impossible anyway, since only total tokens are kept and pricing reloads
                ResourceDescriptor deploymentCostDescription =
                        getResourceDescription(bucket, getPathToDeploymentCosts(roleBasedEntity.getName()));
                // recovered, not composed: attribution is reporting-only and never back-fills anyway, so a
                // failure there must not be reported as a failure to record the spend that enforces. Letting
                // it fail the pair would leave an operator unable to tell the two apart in the logs
                Future<Void> attribution = taskExecutor
                        .submit(() -> updateCostLimit(deploymentCostDescription, cost))
                        .recover(error -> {
                            log.warn("Failed to attribute cost to deployment {}. The enforced total is unaffected",
                                    roleBasedEntity.getName(), error);
                            return Future.succeededFuture();
                        });
                costFuture = Future.all(
                                taskExecutor.submit(() -> updateCostLimit(costResourceDescription, cost)),
                                attribution)
                        .mapEmpty();
            } else {
                costFuture = Future.succeededFuture();
            }

            Future<Void> tokenFuture;
            if (usage == null || usage.getTotalTokens() <= 0) {
                tokenFuture = Future.succeededFuture();
            } else {
                String tokensPath = getPathToTokens(roleBasedEntity.getName());
                ResourceDescriptor tokenResourceDescription = getResourceDescription(bucket, tokensPath);
                tokenFuture = taskExecutor.submit(() -> updateTokenLimit(tokenResourceDescription, usage.getTotalTokens()));
            }

            // Wait for every update to complete
            return Future.all(tokenFuture, costFuture).mapEmpty();
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    public Future<RateLimitResult> limit(ProxyContext context, RoleBasedEntity roleBasedEntity) {
        try {
            // skip checking limits if redis is not available
            if (resourceService == null) {
                return Future.succeededFuture(RateLimitResult.SUCCESS);
            }
            String name = roleBasedEntity.getName();
            Limit limit = getLimitByUser(context, roleBasedEntity);

            if (limit == null || !limit.isPositive()) {
                if (limit == null) {
                    log.warn("Limit is not found for {}", name);
                } else {
                    log.warn("Limit must be positive for {}", name);
                }
                return Future.succeededFuture(new RateLimitResult(HttpStatus.FORBIDDEN, "Access denied", "Access denied", -1));
            }

            return taskExecutor.submit(() -> checkLimit(context, limit, roleBasedEntity));
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    public Future<LimitStats> getLimitStats(RoleBasedEntity roleBasedEntity, ProxyContext context) {
        try {
            // skip checking limits if redis is not available
            if (resourceService == null) {
                return Future.succeededFuture();
            }
            Limit limit = getLimitByUser(context, roleBasedEntity);
            return taskExecutor.submit(() -> getLimitStats(context, limit, roleBasedEntity.getName()));
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    private LimitStats getLimitStats(ProxyContext context, Limit limit, String name) {
        CostLimit costLimit = getCostLimitByUser(context);
        LimitStats limitStats = create(limit, costLimit);
        long timestamp = System.currentTimeMillis();
        collectTokenLimitStats(context, limitStats, timestamp, name);
        collectRequestLimitStats(context, limitStats, timestamp, name);
        collectCostLimitStats(context, limitStats, timestamp);
        return limitStats;
    }

    /**
     * Collects limits and rolling usage for every deployment the caller can access.
     *
     * <p>The key set comes from config, so a deployment the caller can no longer access cannot be reported
     * and one that was never used is still reported - with its real limits against zeros, assembled without
     * touching storage. Which records to read is decided by a single recursive listing of the caller's
     * {@code limits/} folder rather than by asking storage for every expected key, so an installation with
     * many models does not pay a lookup per model.
     *
     * <p>Reads are pipelined in chunks rather than issued as one round-trip, so this is not an atomic
     * snapshot of the counters. One timestamp is shared across the projection instead, so every window in
     * the response is computed against the same instant.
     *
     * @param dropEmpty omit deployments whose every window is zero, which is what separates
     *                  {@code GET /v1/user/usage} from {@code GET /v1/user/limits}
     */
    public Future<UserLimitStats> getUserStats(
            ProxyContext context, List<? extends RoleBasedEntity> deployments, boolean dropEmpty) {
        try {
            // skip checking limits if redis is not available
            if (resourceService == null) {
                return Future.succeededFuture();
            }
            return taskExecutor.submit(() -> collectUserStats(context, deployments, dropEmpty));
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    private UserLimitStats collectUserStats(
            ProxyContext context, List<? extends RoleBasedEntity> deployments, boolean dropEmpty) {
        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        // one instant for the age cutoff and the projection alike, so no window can disagree with another
        long timestamp = System.currentTimeMillis();

        Map<String, LimitStats> collected = new TreeMap<>();
        Map<String, Counter> counters = new HashMap<>();
        for (RoleBasedEntity deployment : deployments) {
            String name = deployment.getName();
            Limit limit = getLimitByUser(context, deployment);
            if (limit == null) {
                log.warn("Limit is not found for {}", name);
                continue;
            }
            // DEFAULT_COST_LIMIT leaves every cost window at the unlimited sentinel: an entry reports the
            // deployment's attributed spend, and only the global budget can cap it
            LimitStats limitStats = create(limit, DEFAULT_COST_LIMIT);
            Map<String, Counter> deploymentCounters = new HashMap<>();
            try {
                deploymentCounters.put(recordPath(bucketLocation, getPathToTokens(name)),
                        new Counter(limitStats, RecordType.TOKENS));
                deploymentCounters.put(recordPath(bucketLocation, getPathToRequests(name)),
                        new Counter(limitStats, RecordType.REQUESTS));
                deploymentCounters.put(recordPath(bucketLocation, getPathToDeploymentCosts(name)),
                        new Counter(limitStats, RecordType.COSTS));
            } catch (RuntimeException e) {
                // A name that cannot form a resource path: a brace or a quote fails URL decoding, an
                // over-long one fails the path-size check. Its counters were never written either, so skip
                // it rather than fail the response for every other deployment. Broad on purpose - the only
                // thing these three calls do is build a path, and the size check reports an
                // IllegalArgumentException, which the controller otherwise maps to 401 for an unresolvable
                // caller
                log.warn("Skipping deployment {}: its limit records have no valid path", name, e);
                continue;
            }
            collected.put(name, limitStats);
            counters.putAll(deploymentCounters);
        }

        // the caller's budget and the spend against it, held apart from the per-deployment entries; its
        // record is a sibling of theirs, so a deployment named "costs" lands on "costs/costs" and cannot
        // collide with it
        LimitStats costStats = create(DEFAULT_LIMIT, getCostLimitByUser(context));
        counters.put(recordPath(bucketLocation, getPathToCosts()), new Counter(costStats, RecordType.COSTS));

        for (Pair<ResourceItemMetadata, String> loaded : readCounters(bucketLocation, counters.keySet(), timestamp)) {
            String path = loaded.getKey().getDescriptor().getAbsoluteFilePath();
            Counter counter = counters.get(path);
            try {
                counter.type().collect(loaded.getValue(), counter.stats(), timestamp);
            } catch (RuntimeException e) {
                // A document that no longer parses leaves that one window at zero rather than failing the
                // whole report. It must not propagate: ProxyUtil.convertToObject reports a parse failure as
                // an IllegalArgumentException, which the controller maps to 401 for an unresolvable caller,
                // so one corrupt record would otherwise log the caller out
                log.warn("Ignoring unreadable limit record {}", path, e);
            }
        }

        UserLimitStats userLimitStats = new UserLimitStats();
        userLimitStats.setMinuteCostStats(costStats.getMinuteCostStats());
        userLimitStats.setDayCostStats(costStats.getDayCostStats());
        userLimitStats.setWeekCostStats(costStats.getWeekCostStats());
        userLimitStats.setMonthCostStats(costStats.getMonthCostStats());

        if (dropEmpty) {
            collected.values().removeIf(stats -> !hasUsage(stats));
        }
        userLimitStats.setDeployments(new LinkedHashMap<>(collected));

        return userLimitStats;
    }

    /**
     * Lists the caller's {@code limits/} folder and reads the bodies of the records that belong to the
     * response, ignoring any other name the listing turns up. A record last written before the widest
     * window opened is left unread: {@link RateWindow#MONTH} keeps 30 one-day intervals, so nothing older
     * can still project to a non-zero figure.
     */
    private List<Pair<ResourceItemMetadata, String>> readCounters(
            String bucketLocation, Set<String> wanted, long timestamp) {
        ResourceDescriptor folder = ResourceDescriptorFactory
                .fromEncoded(ResourceTypes.LIMIT, bucketLocation, bucketLocation, null);

        List<ResourceItemMetadata> items = new ArrayList<>();
        String nextToken = null;
        do {
            ResourceFolderMetadata page = resourceService.getFolderMetadata(folder, nextToken, LIST_PAGE_SIZE, true);
            if (page == null) {
                // a root folder is never reported as missing, so this only guards against a provider
                // returning nothing mid-pagination - keep what was already collected
                break;
            }
            for (MetadataBase item : page.getItems()) {
                if (item instanceof ResourceItemMetadata metadata
                        && wanted.contains(metadata.getDescriptor().getAbsoluteFilePath())) {
                    items.add(metadata);
                }
            }
            // decoded before being fed back: the token is percent-encoded for HTTP clients, while the blob
            // provider takes the marker verbatim. Re-sending it encoded resumes past the true marker for any
            // key holding an escapable character - a space encodes to %20, which sorts after it - silently
            // skipping the records in between
            nextToken = UrlUtil.decodePath(page.getNextToken());
        } while (nextToken != null);

        List<Pair<ResourceItemMetadata, String>> loaded = new ArrayList<>();
        // locked: counters are mutated through computeResource, and an unlocked blob fallback writes the
        // stale blob value back as synced, which would overwrite a concurrent increment and drop its pending
        // flush. Read-only sweeps do not opt in, so the config rebuild keeps its lock-free fan-out
        resourceService.load(items, loaded, timestamp - RateWindow.MONTH.window(), true);
        return loaded;
    }

    private static boolean hasUsage(LimitStats stats) {
        return stats.getMinuteTokenStats().getUsed() > 0
                || stats.getDayTokenStats().getUsed() > 0
                || stats.getWeekTokenStats().getUsed() > 0
                || stats.getMonthTokenStats().getUsed() > 0
                || stats.getHourRequestStats().getUsed() > 0
                || stats.getDayRequestStats().getUsed() > 0
                || stats.getMinuteCostStats().getUsed().signum() > 0
                || stats.getDayCostStats().getUsed().signum() > 0
                || stats.getWeekCostStats().getUsed().signum() > 0
                || stats.getMonthCostStats().getUsed().signum() > 0;
    }

    private String recordPath(String bucketLocation, String path) {
        return getResourceDescription(bucketLocation, path).getAbsoluteFilePath();
    }

    private record Counter(LimitStats stats, RecordType type) {
    }

    private enum RecordType {
        TOKENS {
            @Override
            void collect(String json, LimitStats stats, long timestamp) {
                collectTokenLimitStats(json, stats, timestamp);
            }
        },
        REQUESTS {
            @Override
            void collect(String json, LimitStats stats, long timestamp) {
                collectRequestLimitStats(json, stats, timestamp);
            }
        },
        COSTS {
            @Override
            void collect(String json, LimitStats stats, long timestamp) {
                collectCostLimitStats(json, stats, timestamp);
            }
        };

        abstract void collect(String json, LimitStats stats, long timestamp);
    }

    private void collectTokenLimitStats(ProxyContext context, LimitStats limitStats, long timestamp, String name) {
        ResourceDescriptor resourceDescription = getResourceDescription(context, getPathToTokens(name));
        collectTokenLimitStats(resourceService.getResource(resourceDescription), limitStats, timestamp);
    }

    private static void collectTokenLimitStats(String json, LimitStats limitStats, long timestamp) {
        TokenRateLimit rateLimit = ProxyUtil.convertToObject(json, TokenRateLimit.class);
        if (rateLimit == null) {
            return;
        }
        rateLimit.update(timestamp, limitStats);
    }

    private void collectRequestLimitStats(ProxyContext context, LimitStats limitStats, long timestamp, String name) {
        ResourceDescriptor resourceDescription = getResourceDescription(context, getPathToRequests(name));
        collectRequestLimitStats(resourceService.getResource(resourceDescription), limitStats, timestamp);
    }

    private static void collectRequestLimitStats(String json, LimitStats limitStats, long timestamp) {
        RequestRateLimit rateLimit = ProxyUtil.convertToObject(json, RequestRateLimit.class);
        if (rateLimit == null) {
            return;
        }
        rateLimit.update(timestamp, limitStats);
    }

    private void collectCostLimitStats(ProxyContext context, LimitStats limitStats, long timestamp) {
        ResourceDescriptor resourceDescription = getResourceDescription(context, getPathToCosts());
        collectCostLimitStats(resourceService.getResource(resourceDescription), limitStats, timestamp);
    }

    private static void collectCostLimitStats(String json, LimitStats limitStats, long timestamp) {
        CostRateLimit rateLimit = ProxyUtil.convertToObject(json, CostRateLimit.class);
        if (rateLimit == null) {
            return;
        }
        rateLimit.update(timestamp, limitStats);
    }

    private LimitStats create(Limit limit, CostLimit costLimit) {
        LimitStats limitStats = new LimitStats();

        // Token limits
        ItemLimitStats dayTokenStats = new ItemLimitStats();
        dayTokenStats.setTotal(limit.getDay());
        limitStats.setDayTokenStats(dayTokenStats);

        ItemLimitStats minuteTokenStats = new ItemLimitStats();
        minuteTokenStats.setTotal(limit.getMinute());
        limitStats.setMinuteTokenStats(minuteTokenStats);

        ItemLimitStats weekTokenStats = new ItemLimitStats();
        weekTokenStats.setTotal(limit.getWeek());
        limitStats.setWeekTokenStats(weekTokenStats);

        ItemLimitStats monthTokenStats = new ItemLimitStats();
        monthTokenStats.setTotal(limit.getMonth());
        limitStats.setMonthTokenStats(monthTokenStats);

        ItemLimitStats hourRequestStats = new ItemLimitStats();
        hourRequestStats.setTotal(limit.getRequestHour());
        limitStats.setHourRequestStats(hourRequestStats);

        ItemLimitStats dayRequestStats = new ItemLimitStats();
        dayRequestStats.setTotal(limit.getRequestDay());
        limitStats.setDayRequestStats(dayRequestStats);

        if (costLimit != null) {
            CostItemLimitStats minuteCostStats = new CostItemLimitStats();
            minuteCostStats.setTotal(costLimit.getMinute());
            limitStats.setMinuteCostStats(minuteCostStats);

            CostItemLimitStats dayCostStats = new CostItemLimitStats();
            dayCostStats.setTotal(costLimit.getDay());
            limitStats.setDayCostStats(dayCostStats);

            CostItemLimitStats weekCostStats = new CostItemLimitStats();
            weekCostStats.setTotal(costLimit.getWeek());
            limitStats.setWeekCostStats(weekCostStats);

            CostItemLimitStats monthCostStats = new CostItemLimitStats();
            monthCostStats.setTotal(costLimit.getMonth());
            limitStats.setMonthCostStats(monthCostStats);
        }

        return limitStats;
    }

    private ResourceDescriptor getResourceDescription(ProxyContext context, String path) {
        // use bucket location of request's initiator,
        // e.g. user -> core -> application -> core -> model, limits must be applied to the user by JWT
        // e.g. service -> core -> application -> core -> model, limits must be applied to service by API key
        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        return getResourceDescription(bucketLocation, path);
    }

    private ResourceDescriptor getResourceDescription(String bucketLocation, String path) {
        return ResourceDescriptorFactory.fromEncoded(ResourceTypes.LIMIT, bucketLocation, bucketLocation, path);
    }

    private RateLimitResult checkLimit(ProxyContext context, Limit limit, RoleBasedEntity roleBasedEntity) {
        long timestamp = System.currentTimeMillis();

        // Check token limits
        RateLimitResult tokenResult = checkTokenLimit(context, limit, timestamp, roleBasedEntity);
        if (tokenResult.status() != HttpStatus.OK) {
            return tokenResult;
        }

        // Check request limits
        RateLimitResult requestResult = checkRequestLimit(context, limit, timestamp, roleBasedEntity);
        if (requestResult.status() != HttpStatus.OK) {
            return requestResult;
        }

        // Check cost limits
        CostLimit costLimit = getCostLimitByUser(context);
        return checkCostLimit(context, costLimit, timestamp);
    }

    private RateLimitResult checkCostLimit(ProxyContext context, CostLimit costLimit, long timestamp) {
        String costsPath = getPathToCosts();
        ResourceDescriptor resourceDescription = getResourceDescription(context, costsPath);
        String prevValue = resourceService.getResource(resourceDescription);
        CostRateLimit rateLimit = ProxyUtil.convertToObject(prevValue, CostRateLimit.class);
        if (rateLimit == null) {
            return RateLimitResult.SUCCESS;
        }
        return rateLimit.check(timestamp, costLimit);
    }

    private RateLimitResult checkTokenLimit(ProxyContext context, Limit limit, long timestamp, RoleBasedEntity roleBasedEntity) {
        String tokensPath = getPathToTokens(roleBasedEntity.getName());
        ResourceDescriptor resourceDescription = getResourceDescription(context, tokensPath);
        String prevValue = resourceService.getResource(resourceDescription);
        TokenRateLimit rateLimit = ProxyUtil.convertToObject(prevValue, TokenRateLimit.class);
        if (rateLimit == null) {
            return RateLimitResult.SUCCESS;
        }
        return rateLimit.update(timestamp, limit);
    }

    private RateLimitResult checkRequestLimit(ProxyContext context, Limit limit, long timestamp, RoleBasedEntity roleBasedEntity) {
        String tokensPath = getPathToRequests(roleBasedEntity.getName());
        ResourceDescriptor resourceDescription = getResourceDescription(context, tokensPath);
        // pass array to hold rate limit result returned by the function to compute the resource
        RateLimitResult[] result = new RateLimitResult[1];
        resourceService.computeResource(resourceDescription, json -> updateRequestLimit(json, timestamp, limit, result));
        return result[0];
    }

    private String updateRequestLimit(String json, long timestamp, Limit limit, RateLimitResult[] result) {
        RequestRateLimit rateLimit = ProxyUtil.convertToObject(json, RequestRateLimit.class);
        if (rateLimit == null) {
            rateLimit = new RequestRateLimit();
        }
        result[0] = rateLimit.check(timestamp, limit, 1);
        return ProxyUtil.convertToString(rateLimit);
    }

    private Void updateTokenLimit(ResourceDescriptor resourceDescription, long totalUsedTokens) {
        resourceService.computeResource(resourceDescription, json -> updateTokenLimit(json, totalUsedTokens));
        return null;
    }

    private String updateTokenLimit(String json, long totalUsedTokens) {
        TokenRateLimit rateLimit = ProxyUtil.convertToObject(json, TokenRateLimit.class);
        if (rateLimit == null) {
            rateLimit = new TokenRateLimit();
        }
        long timestamp = System.currentTimeMillis();
        rateLimit.add(timestamp, totalUsedTokens);
        return ProxyUtil.convertToString(rateLimit);
    }

    private Void updateCostLimit(ResourceDescriptor resourceDescription, BigDecimal cost) {
        resourceService.computeResource(resourceDescription, json -> updateCostLimit(json, cost));
        return null;
    }

    private String updateCostLimit(String json, BigDecimal cost) {
        CostRateLimit rateLimit = ProxyUtil.convertToObject(json, CostRateLimit.class);
        if (rateLimit == null) {
            rateLimit = new CostRateLimit();
        }
        long timestamp = System.currentTimeMillis();
        rateLimit.add(timestamp, cost);
        return ProxyUtil.convertToString(rateLimit);
    }

    private Limit getLimitByUser(ProxyContext context, RoleBasedEntity roleBasedEntity) {
        String name = roleBasedEntity.getName();
        List<String> userRoles;
        if (roleBasedEntity.getUserRoles() == null) {
            // find limits for all user roles
            userRoles = context.getUserRoles();
        } else {
            // find limits for user roles which match to required roles
            userRoles = context.getUserRoles().stream().filter(role -> roleBasedEntity.getUserRoles().contains(role)).toList();
        }
        Map<String, Role> roles = context.getConfig().getRoles();
        Limit defaultUserLimit = getLimit(roles, DEFAULT_USER_ROLE, name, DEFAULT_LIMIT);
        if (userRoles.isEmpty()) {
            return defaultUserLimit;
        }
        Limit limit = null;
        for (String userRole : userRoles) {
            Limit candidate = getLimit(roles, userRole, name, null);
            if (candidate != null) {
                if (limit == null) {
                    limit = new Limit();
                    limit.setMinute(candidate.getMinute());
                    limit.setRequestHour(candidate.getRequestHour());
                    limit.setRequestDay(candidate.getRequestDay());
                    limit.setDay(candidate.getDay());
                    limit.setWeek(candidate.getWeek());
                    limit.setMonth(candidate.getMonth());
                } else {
                    limit.setMinute(Math.max(candidate.getMinute(), limit.getMinute()));
                    limit.setDay(Math.max(candidate.getDay(), limit.getDay()));
                    limit.setRequestDay(Math.max(candidate.getRequestDay(), limit.getRequestDay()));
                    limit.setRequestHour(Math.max(candidate.getRequestHour(), limit.getRequestHour()));
                    limit.setWeek(Math.max(candidate.getWeek(), limit.getWeek()));
                    limit.setMonth(Math.max(candidate.getMonth(), limit.getMonth()));
                }
            }
        }
        return limit == null ? defaultUserLimit : limit;
    }

    private CostLimit getCostLimitByUser(ProxyContext context) {
        List<String> userRoles = context.getUserRoles();
        Map<String, Role> roles = context.getConfig().getRoles();
        CostLimit defaultUserCostLimit = getCostLimit(roles, DEFAULT_USER_ROLE, DEFAULT_COST_LIMIT);
        if (userRoles.isEmpty()) {
            return defaultUserCostLimit;
        }
        CostLimit costLimit = null;
        for (String userRole : userRoles) {
            CostLimit candidate = getCostLimit(roles, userRole, null);
            if (candidate != null) {
                if (costLimit == null) {
                    costLimit = new CostLimit();
                    costLimit.setMinute(candidate.getMinute());
                    costLimit.setDay(candidate.getDay());
                    costLimit.setWeek(candidate.getWeek());
                    costLimit.setMonth(candidate.getMonth());
                } else {
                    // Use the maximum limit for each time period
                    costLimit.setMinute(costLimit.getMinute().max(candidate.getMinute()));
                    costLimit.setDay(costLimit.getDay().max(candidate.getDay()));
                    costLimit.setWeek(costLimit.getWeek().max(candidate.getWeek()));
                    costLimit.setMonth(costLimit.getMonth().max(candidate.getMonth()));
                }
            }
        }
        return costLimit == null ? defaultUserCostLimit : costLimit;
    }

    private static String getPathToTokens(String name) {
        return String.format("%s/tokens", name);
    }

    private static String getPathToRequests(String name) {
        return String.format("%s/requests", name);
    }

    private static String getPathToCosts() {
        return "costs";
    }

    private static String getPathToDeploymentCosts(String name) {
        return String.format("%s/costs", name);
    }

    private static Limit getLimit(Map<String, Role> roles, String userRole, String name, Limit defaultLimit) {
        return Optional.ofNullable(roles.get(userRole))
                .map(role -> role.getLimits().get(name))
                .orElse(defaultLimit);
    }

    private static CostLimit getCostLimit(Map<String, Role> roles, String userRole, CostLimit defaultCostLimit) {
        return Optional.ofNullable(roles.get(userRole))
                .map(Role::getCostLimit)
                .orElse(defaultCostLimit);
    }
}
