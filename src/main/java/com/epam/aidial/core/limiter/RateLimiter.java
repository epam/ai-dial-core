package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.data.ItemLimitStats;
import com.epam.aidial.core.data.LimitStats;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class RateLimiter {

    private static final Limit DEFAULT_LIMIT = new Limit();
    private static final String DEFAULT_USER_ROLE = "default";

    private final Vertx vertx;

    private final ResourceService resourceService;

    public Future<Void> increase(ProxyContext context) {
        try {
            // skip checking limits if redis is not available
            if (resourceService == null) {
                return Future.succeededFuture();
            }

            TokenUsage usage = context.getTokenUsage();

            if (usage == null || usage.getTotalTokens() <= 0) {
                return Future.succeededFuture();
            }

            String tokensPath = getPathToTokens(context.getDeployment().getName());
            ResourceDescription resourceDescription = getResourceDescription(context, tokensPath);
            return vertx.executeBlocking(() -> updateTokenLimit(resourceDescription, usage.getTotalTokens()), false);
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    public Future<RateLimitResult> limit(ProxyContext context) {
        try {
            // skip checking limits if redis is not available
            if (resourceService == null) {
                return Future.succeededFuture(RateLimitResult.SUCCESS);
            }
            Key key = context.getKey();
            String deploymentName = context.getDeployment().getName();
            Limit limit;
            if (key == null) {
                limit = getLimitByUser(context, deploymentName);
            } else {
                limit = getLimitByApiKey(context, deploymentName);
            }

            if (limit == null || !limit.isPositive()) {
                if (limit == null) {
                    log.warn("Limit is not found for deployment: {}", deploymentName);
                } else {
                    log.warn("Limit must be positive for deployment: {}", deploymentName);
                }
                return Future.succeededFuture(new RateLimitResult(HttpStatus.FORBIDDEN, "Access denied"));
            }

            return vertx.executeBlocking(() -> checkLimit(context, limit), false);
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    public Future<LimitStats> getLimitStats(String deploymentName, ProxyContext context) {
        try {
            // skip checking limits if redis is not available
            if (resourceService == null) {
                return Future.succeededFuture();
            }
            Key key = context.getKey();
            Limit limit;
            if (key == null) {
                limit = getLimitByUser(context, deploymentName);
            } else {
                limit = getLimitByApiKey(context, deploymentName);
            }
            if (limit == null) {
                log.warn("Limit is not found. Trace: {}. Span: {}. Key: {}. User sub: {}. Deployment: {}",
                        context.getTraceId(), context.getSpanId(), key == null ? null : key.getProject(),
                        context.getUserSub(), deploymentName);
                return Future.succeededFuture();
            }
            return vertx.executeBlocking(() -> getLimitStats(context, limit, deploymentName), false);
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    private LimitStats getLimitStats(ProxyContext context, Limit limit, String deploymentName) {
        LimitStats limitStats = create(limit);
        long timestamp = System.currentTimeMillis();
        collectTokenLimitStats(context, limitStats, timestamp, deploymentName);
        collectRequestLimitStats(context, limitStats, timestamp, deploymentName);
        return limitStats;
    }

    private void collectTokenLimitStats(ProxyContext context, LimitStats limitStats, long timestamp, String deploymentName) {
        String tokensPath = getPathToTokens(deploymentName);
        ResourceDescription resourceDescription = getResourceDescription(context, tokensPath);
        String json = resourceService.getResource(resourceDescription, true);
        TokenRateLimit rateLimit = ProxyUtil.convertToObject(json, TokenRateLimit.class);
        if (rateLimit == null) {
            return;
        }
        rateLimit.update(timestamp, limitStats);
    }

    private void collectRequestLimitStats(ProxyContext context, LimitStats limitStats, long timestamp, String deploymentName) {
        String requestsPath = getPathToRequests(deploymentName);
        ResourceDescription resourceDescription = getResourceDescription(context, requestsPath);
        String json = resourceService.getResource(resourceDescription, true);
        RequestRateLimit rateLimit = ProxyUtil.convertToObject(json, RequestRateLimit.class);
        if (rateLimit == null) {
            return;
        }
        rateLimit.update(timestamp, limitStats);
    }

    private LimitStats create(Limit limit) {
        LimitStats limitStats = new LimitStats();

        ItemLimitStats dayTokenStats = new ItemLimitStats();
        dayTokenStats.setTotal(limit.getDay());
        limitStats.setDayTokenStats(dayTokenStats);

        ItemLimitStats minuteTokenStats = new ItemLimitStats();
        minuteTokenStats.setTotal(limit.getMinute());
        limitStats.setMinuteTokenStats(minuteTokenStats);

        ItemLimitStats hourRequestStats = new ItemLimitStats();
        hourRequestStats.setTotal(limit.getRequestHour());
        limitStats.setHourRequestStats(hourRequestStats);

        ItemLimitStats dayRequestStats = new ItemLimitStats();
        dayRequestStats.setTotal(limit.getRequestDay());
        limitStats.setDayRequestStats(dayRequestStats);

        return limitStats;
    }

    private ResourceDescription getResourceDescription(ProxyContext context, String path) {
        String bucketLocation = BlobStorageUtil.buildUserBucket(context);
        return ResourceDescription.fromEncoded(ResourceType.LIMIT, bucketLocation, bucketLocation, path);
    }

    private RateLimitResult checkLimit(ProxyContext context, Limit limit) {
        long timestamp = System.currentTimeMillis();
        RateLimitResult tokenResult = checkTokenLimit(context, limit, timestamp);
        if (tokenResult.status() != HttpStatus.OK) {
            return tokenResult;
        }
        return checkRequestLimit(context, limit, timestamp);
    }

    private RateLimitResult checkTokenLimit(ProxyContext context, Limit limit, long timestamp) {
        String tokensPath = getPathToTokens(context.getDeployment().getName());
        ResourceDescription resourceDescription = getResourceDescription(context, tokensPath);
        String prevValue = resourceService.getResource(resourceDescription);
        TokenRateLimit rateLimit = ProxyUtil.convertToObject(prevValue, TokenRateLimit.class);
        if (rateLimit == null) {
            return RateLimitResult.SUCCESS;
        }
        return rateLimit.update(timestamp, limit);
    }

    private RateLimitResult checkRequestLimit(ProxyContext context, Limit limit, long timestamp) {
        String tokensPath = getPathToRequests(context.getDeployment().getName());
        ResourceDescription resourceDescription = getResourceDescription(context, tokensPath);
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

    private Void updateTokenLimit(ResourceDescription resourceDescription, long totalUsedTokens) {
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

    private Limit getLimitByApiKey(ProxyContext context, String deploymentName) {
        // API key has always one role
        Role role = context.getConfig().getRoles().get(context.getKey().getRole());

        if (role == null) {
            log.warn("Role is not found for key: {}", context.getKey().getProject());
            return null;
        }

        return role.getLimits().get(deploymentName);
    }

    private Limit getLimitByUser(ProxyContext context, String deploymentName) {
        List<String> userRoles = context.getUserRoles();
        Map<String, Role> roles = context.getConfig().getRoles();
        Limit defaultUserLimit = getLimit(roles, DEFAULT_USER_ROLE, deploymentName, DEFAULT_LIMIT);
        if (userRoles.isEmpty()) {
            return defaultUserLimit;
        }
        Limit limit = null;
        for (String userRole : userRoles) {
            Limit candidate = getLimit(roles, userRole, deploymentName, null);
            if (candidate != null) {
                if (limit == null) {
                    limit = new Limit();
                    limit.setMinute(candidate.getMinute());
                    limit.setRequestHour(candidate.getRequestHour());
                    limit.setRequestDay(candidate.getRequestDay());
                    limit.setDay(candidate.getDay());
                } else {
                    limit.setMinute(Math.max(candidate.getMinute(), limit.getMinute()));
                    limit.setDay(Math.max(candidate.getDay(), limit.getDay()));
                    limit.setRequestDay(Math.max(candidate.getRequestDay(), limit.getRequestDay()));
                    limit.setRequestHour(Math.max(candidate.getRequestHour(), limit.getRequestHour()));
                }
            }
        }
        return limit == null ? defaultUserLimit : limit;
    }

    private static String getPathToTokens(String deploymentName) {
        return String.format("%s/tokens", deploymentName);
    }

    private static String getPathToRequests(String deploymentName) {
        return String.format("%s/requests", deploymentName);
    }

    private static Limit getLimit(Map<String, Role> roles, String userRole, String deploymentName, Limit defaultLimit) {
        return Optional.ofNullable(roles.get(userRole))
                .map(role -> role.getLimits().get(deploymentName))
                .orElse(defaultLimit);
    }

}
