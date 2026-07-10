package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.BaseRedisNodes;
import org.redisson.api.redisnode.RedisNodes;

import java.util.List;

@AllArgsConstructor
@Slf4j
public class HealthCheckController {

    private static final List<RedisNodes<? extends BaseRedisNodes>> CANDIDATES = List.of(RedisNodes.CLUSTER, RedisNodes.SINGLE,
            RedisNodes.MASTER_SLAVE, RedisNodes.SENTINEL_MASTER_SLAVE);

    private final RedissonClient redissonClient;
    private final AsyncTaskExecutor taskExecutor;

    public void handle(HttpServerRequest request) {
        taskExecutor.submit(() -> getRedisNodes().pingAll())
                .onSuccess(ignore -> respond(request, HttpStatus.OK)).onFailure(error -> {
                    log.error("liveliness check probe is failed", error);
                    respond(request, HttpStatus.INTERNAL_SERVER_ERROR);
                });
    }

    private BaseRedisNodes getRedisNodes() {
        for (RedisNodes<? extends BaseRedisNodes> candidate : CANDIDATES) {
            try {
                return redissonClient.getRedisNodes(candidate);
            } catch (IllegalArgumentException ignore) {
                // bad candidate
            }
        }
        throw new IllegalArgumentException("Unknown redis nodes");
    }

    private void respond(HttpServerRequest request, HttpStatus status) {
        request.response().setStatusCode(status.getCode()).end();
    }
}