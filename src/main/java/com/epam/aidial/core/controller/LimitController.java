package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LimitController {

    private final Proxy proxy;

    private final ProxyContext context;

    public LimitController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
    }

    public Future<?> getLimits(String deploymentName) {
        proxy.getRateLimiter().getLimitStats(deploymentName, context).onSuccess(limitStats -> {
            if (limitStats == null) {
                context.respond(HttpStatus.NOT_FOUND);
            } else {
                context.respond(HttpStatus.OK, limitStats);
            }
        }).onFailure(error -> {
            log.error("Failed to get limit stats", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to get limit stats for deployment=%s".formatted(deploymentName));
        });
        return Future.succeededFuture();
    }
}
