package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.data.LimitStats;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.TokenLimitStats;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class RateLimiter {

    private static final Limit DEFAULT_LIMIT = new Limit();

    private final Vertx vertx;

    private final ResourceService resourceService;

    public Future<Void> increase(ProxyContext context) {
        try {
            // skip checking limits if redis is not available
            if (resourceService == null) {
                return Future.succeededFuture();
            }

            Deployment deployment = context.getDeployment();
            TokenUsage usage = context.getTokenUsage();

            if (usage == null || usage.getTotalTokens() <= 0) {
                return Future.succeededFuture();
            }

            ResourceDescription resourceDescription = getResourceDescription(deployment.getName(), context);
            return vertx.executeBlocking(() -> updateLimit(resourceDescription, usage.getTotalTokens()));
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
            Deployment deployment = context.getDeployment();
            Limit limit;
            if (key == null) {
                limit = getLimitByUser(context);
            } else {
                limit = getLimitByApiKey(context, deployment.getName());
            }

            if (limit == null || !limit.isPositive()) {
                if (limit == null) {
                    log.warn("Limit is not found for deployment: {}", deployment.getName());
                } else {
                    log.warn("Limit must be positive for deployment: {}", deployment.getName());
                }
                return Future.succeededFuture(new RateLimitResult(HttpStatus.FORBIDDEN, "Access denied"));
            }

            ResourceDescription resourceDescription = getResourceDescription(deployment.getName(), context);
            return vertx.executeBlocking(() -> checkLimit(resourceDescription, limit));
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
                // don't support user limits yet
                return Future.succeededFuture();
            } else {
                limit = getLimitByApiKey(context, deploymentName);
            }
            if (limit == null) {
                log.warn("Limit is not found. Trace: {}. Span: {}. Key: {}. Deployment: {}", context.getTraceId(), context.getSpanId(), key.getProject(), deploymentName);
                return Future.succeededFuture();
            }
            ResourceDescription resourceDescription = getResourceDescription(deploymentName, context);
            return vertx.executeBlocking(() -> getLimitStats(resourceDescription, limit));
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    private LimitStats getLimitStats(ResourceDescription resourceDescription, Limit limit) {
        String json = resourceService.getResource(resourceDescription, true);
        LimitStats limitStats = create(limit);
        RateLimit rateLimit = ProxyUtil.convertToObject(json, RateLimit.class);
        if (rateLimit == null) {
            return limitStats;
        }
        long timestamp = System.currentTimeMillis();
        rateLimit.update(timestamp, limitStats);
        return limitStats;
    }

    private LimitStats create(Limit limit) {
        LimitStats limitStats = new LimitStats();
        TokenLimitStats dayTokenStats = new TokenLimitStats();
        dayTokenStats.setTotal(limit.getDay());
        limitStats.setDayTokenStats(dayTokenStats);
        TokenLimitStats minuteTokenStats = new TokenLimitStats();
        minuteTokenStats.setTotal(limit.getMinute());
        limitStats.setMinuteTokenStats(minuteTokenStats);
        return limitStats;
    }

    private ResourceDescription getResourceDescription(String deploymentName, ProxyContext context) {
        String path = getPath(deploymentName);
        String bucketLocation = BlobStorageUtil.buildUserBucket(context);
        return ResourceDescription.fromEncoded(ResourceType.LIMIT, bucketLocation, bucketLocation, path);
    }

    private RateLimitResult checkLimit(ResourceDescription resourceDescription, Limit limit) {
        String prevValue = resourceService.getResource(resourceDescription);
        RateLimit rateLimit = ProxyUtil.convertToObject(prevValue, RateLimit.class);
        if (rateLimit == null) {
            return RateLimitResult.SUCCESS;
        }
        long timestamp = System.currentTimeMillis();
        return rateLimit.update(timestamp, limit);
    }

    private Void updateLimit(ResourceDescription resourceDescription, long totalUsedTokens) {
        resourceService.computeResource(resourceDescription, json -> updateLimit(json, totalUsedTokens));
        return null;
    }

    @SneakyThrows
    private String updateLimit(String json, long totalUsedTokens) {
        RateLimit rateLimit = ProxyUtil.convertToObject(json, RateLimit.class);
        if (rateLimit == null) {
            rateLimit = new RateLimit();
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

    private Limit getLimitByUser(ProxyContext context) {
        List<String> userRoles = context.getUserRoles();
        Limit defaultUserLimit = getDefaultUserLimit(context.getDeployment());
        if (userRoles.isEmpty()) {
            return defaultUserLimit;
        }
        String deploymentName = context.getDeployment().getName();
        Map<String, Role> userRoleToDeploymentLimits = context.getConfig().getRoles();
        long minuteLimit = 0;
        long dayLimit = 0;
        for (String userRole : userRoles) {
            Limit limit = Optional.ofNullable(userRoleToDeploymentLimits.get(userRole))
                    .map(role -> role.getLimits().get(deploymentName))
                    .orElse(defaultUserLimit);
            minuteLimit = Math.max(minuteLimit, limit.getMinute());
            dayLimit = Math.max(dayLimit, limit.getDay());
        }
        Limit limit = new Limit();
        limit.setMinute(minuteLimit);
        limit.setDay(dayLimit);
        return limit;
    }

    private static String getPath(String deploymentName) {
        return String.format("%s/tokens", deploymentName);
    }

    private static Limit getDefaultUserLimit(Deployment deployment) {
        if (deployment instanceof Model model) {
            return model.getDefaultUserLimit() == null ? DEFAULT_LIMIT : model.getDefaultUserLimit();
        }
        return DEFAULT_LIMIT;
    }

}
