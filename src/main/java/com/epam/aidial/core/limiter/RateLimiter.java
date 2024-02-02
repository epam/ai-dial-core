package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
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

            String path = getPath(deployment.getName());
            return vertx.executeBlocking(() -> updateLimit(path, context, usage.getTotalTokens()));
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
            Limit limit;
            if (key == null) {
                limit = getLimitByUser(context);
            } else {
                limit = getLimitByApiKey(context);
            }

            Deployment deployment = context.getDeployment();

            if (limit == null || !limit.isPositive()) {
                if (limit == null) {
                    log.warn("Limit is not found for deployment: {}", deployment.getName());
                } else {
                    log.warn("Limit must be positive for deployment: {}", deployment.getName());
                }
                return Future.succeededFuture(new RateLimitResult(HttpStatus.FORBIDDEN, "Access denied"));
            }

            String path = getPath(deployment.getName());
            return vertx.executeBlocking(() -> checkLimit(path, limit, context));
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    private RateLimitResult checkLimit(String path, Limit limit, ProxyContext context) throws Exception {
        RateLimit rateLimit;
        String bucketLocation = BlobStorageUtil.buildUserBucket(context);
        ResourceDescription resourceDescription = ResourceDescription.fromEncoded(ResourceType.LIMIT, bucketLocation, bucketLocation, path);
        String prevValue = resourceService.getResource(resourceDescription);
        if (prevValue == null) {
            return RateLimitResult.SUCCESS;
        } else {
            rateLimit = ProxyUtil.MAPPER.readValue(prevValue, RateLimit.class);
        }
        long timestamp = System.currentTimeMillis();
        return rateLimit.update(timestamp, limit);
    }

    private Void updateLimit(String path, ProxyContext context, long totalUsedTokens) {
        String bucketLocation = BlobStorageUtil.buildUserBucket(context);
        ResourceDescription resourceDescription = ResourceDescription.fromEncoded(ResourceType.LIMIT, bucketLocation, bucketLocation, path);
        resourceService.computeResource(resourceDescription, json -> updateLimit(json, totalUsedTokens));
        return null;
    }

    @SneakyThrows
    private String updateLimit(String json, long totalUsedTokens) {
        RateLimit rateLimit;
        if (json == null) {
            rateLimit = new RateLimit();
        } else {
            rateLimit = ProxyUtil.MAPPER.readValue(json, RateLimit.class);
        }
        long timestamp = System.currentTimeMillis();
        rateLimit.add(timestamp, totalUsedTokens);
        return ProxyUtil.MAPPER.writeValueAsString(rateLimit);
    }

    private Limit getLimitByApiKey(ProxyContext context) {
        // API key has always one role
        Role role = context.getConfig().getRoles().get(context.getKey().getRole());

        if (role == null) {
            log.warn("Role is not found for key: {}", context.getKey().getProject());
            return null;
        }

        Deployment deployment = context.getDeployment();
        return role.getLimits().get(deployment.getName());
    }

    private Limit getLimitByUser(ProxyContext context) {
        List<String> userRoles = context.getUserRoles();
        Limit defaultUserLimit = getDefaultUserLimit(context.getDeployment());
        if (userRoles.isEmpty()) {
            return defaultUserLimit;
        }
        String deploymentName = context.getDeployment().getName();
        Map<String, Role> userRoleToDeploymentLimits = context.getConfig().getUserRoles();
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
