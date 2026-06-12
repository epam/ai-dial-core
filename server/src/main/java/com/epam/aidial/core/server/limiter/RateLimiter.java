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
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ModelCostCalculator;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class RateLimiter {

    private static final Limit DEFAULT_LIMIT = new Limit();
    private static final CostLimit DEFAULT_COST_LIMIT = new CostLimit();
    private static final String DEFAULT_USER_ROLE = "default";

    private final AsyncTaskExecutor taskExecutor;

    private final ResourceService resourceService;

    public Future<Void> increase(
            Deployment deployment,
            String bucketLocation,
            TokenUsage usage,
            Buffer requestBody,
            Buffer responseBody) {
        try {
            // skip checking limits if redis is not available
            if (resourceService == null) {
                return Future.succeededFuture();
            }

            BigDecimal cost = ModelCostCalculator.calculate(deployment, usage, requestBody, responseBody);
            Future<Void> costFuture;
            if (cost != null && cost.compareTo(BigDecimal.ZERO) > 0) {
                if (usage != null) {
                    usage.setCost(cost);
                    usage.setAggCost(cost);
                }

                String costsPath = getPathToCosts();
                ResourceDescriptor costResourceDescription = getResourceDescription(bucketLocation, costsPath);
                costFuture = taskExecutor.submit(() -> updateCostLimit(costResourceDescription, cost));
            } else {
                costFuture = Future.succeededFuture();
            }

            Future<Void> tokenFuture;
            if (usage == null || usage.getTotalTokens() <= 0) {
                tokenFuture = Future.succeededFuture();
            } else {
                String tokensPath = getPathToTokens(deployment.getName());
                ResourceDescriptor tokenResourceDescription = getResourceDescription(bucketLocation, tokensPath);
                tokenFuture = taskExecutor.submit(() -> updateTokenLimit(tokenResourceDescription, usage.getTotalTokens()));
            }

            // Wait for both updates to complete if both exist
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

    private void collectTokenLimitStats(ProxyContext context, LimitStats limitStats, long timestamp, String name) {
        String tokensPath = getPathToTokens(name);
        ResourceDescriptor resourceDescription = getResourceDescription(context, tokensPath);
        String json = resourceService.getResource(resourceDescription);
        TokenRateLimit rateLimit = ProxyUtil.convertToObject(json, TokenRateLimit.class);
        if (rateLimit == null) {
            return;
        }
        rateLimit.update(timestamp, limitStats);
    }

    private void collectRequestLimitStats(ProxyContext context, LimitStats limitStats, long timestamp, String name) {
        String requestsPath = getPathToRequests(name);
        ResourceDescriptor resourceDescription = getResourceDescription(context, requestsPath);
        String json = resourceService.getResource(resourceDescription);
        RequestRateLimit rateLimit = ProxyUtil.convertToObject(json, RequestRateLimit.class);
        if (rateLimit == null) {
            return;
        }
        rateLimit.update(timestamp, limitStats);
    }

    private void collectCostLimitStats(ProxyContext context, LimitStats limitStats, long timestamp) {
        String costsPath = getPathToCosts();
        ResourceDescriptor resourceDescription = getResourceDescription(context, costsPath);
        String json = resourceService.getResource(resourceDescription);
        CostRateLimit rateLimit = ProxyUtil.convertToObject(json, CostRateLimit.class);
        if (rateLimit == null) {
            return;
        }
        rateLimit.update(timestamp, limitStats);
    }

    private LimitStats create(Limit limit) {
        return create(limit, null);
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
