package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
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

import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class RateLimiter {

    private final Vertx vertx;

    private final ResourceService resourceService;

    public Future<Void> increase(ProxyContext context) {
        try {
            // skip checking limits if redis is not available
            if (resourceService == null) {
                return Future.succeededFuture();
            }
            Key key = context.getKey();
            if (key == null) {
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
                // don't support user limits yet
                return Future.succeededFuture(RateLimitResult.SUCCESS);
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
        resourceService.computeResource(resourceDescription, new Function<String, String>() {
            @SneakyThrows
            @Override
            public String apply(String prevValue) {
                RateLimit rateLimit = ProxyUtil.MAPPER.readValue(prevValue, RateLimit.class);
                long timestamp = System.currentTimeMillis();
                rateLimit.add(timestamp, totalUsedTokens);
                return ProxyUtil.MAPPER.writeValueAsString(rateLimit);
            }
        });
        return null;
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

    private static String getPath(String deploymentName) {
        return String.format("/token/%s", deploymentName);
    }

}
