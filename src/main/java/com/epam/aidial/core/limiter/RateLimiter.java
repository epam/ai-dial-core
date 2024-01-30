package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

@Slf4j
@RequiredArgsConstructor
public class RateLimiter {

    private final Vertx vertx;

    private final LockService lockService;

    private final RedissonClient redis;

    public Future<Void> increase(ProxyContext context) {
        Key key = context.getKey();
        if (key == null) {
            return Future.succeededFuture();
        }
        Deployment deployment = context.getDeployment();
        TokenUsage usage = context.getTokenUsage();

        if (usage == null || usage.getTotalTokens() <= 0) {
            return Future.succeededFuture();
        }

        String redisKey = getRedisKey(key.getKey(), deployment.getName());
        return vertx.executeBlocking(() -> {
            try (var ignore = lockService.lock(redisKey)) {
                RBucket<String>  bucket = redis.getBucket(redisKey, StringCodec.INSTANCE);
                String json = bucket.get();
                RateLimit rateLimit;
                if (json == null) {
                    rateLimit = new RateLimit();
                } else {
                    rateLimit = ProxyUtil.MAPPER.readValue(json, RateLimit.class);
                }
                long timestamp = System.currentTimeMillis();
                rateLimit.add(timestamp, usage.getTotalTokens());
                bucket.set(ProxyUtil.MAPPER.writeValueAsString(rateLimit));
            }
            return null;
        });
    }

    public Future<RateLimitResult> limit(ProxyContext context) {
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

        String redisKey = getRedisKey(key.getKey(), deployment.getName());
        return vertx.executeBlocking(() -> {
            try (var ignore = lockService.lock(redisKey)) {
                RBucket<String>  bucket = redis.getBucket(redisKey, StringCodec.INSTANCE);
                String json = bucket.get();
                RateLimit rateLimit;
                if (json == null) {
                    return RateLimitResult.SUCCESS;
                } else {
                    rateLimit = ProxyUtil.MAPPER.readValue(json, RateLimit.class);
                }
                long timestamp = System.currentTimeMillis();
                RateLimitResult result = rateLimit.update(timestamp, limit);
                bucket.set(ProxyUtil.MAPPER.writeValueAsString(rateLimit));
                return result;
            }
        });
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

    private String getRedisKey(String projectKey, String deploymentName) {
        return String.format("limit.api.key.%s.%s", projectKey, deploymentName);
    }

}
