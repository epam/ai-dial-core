package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

/**
 * Admin-only health endpoint for the configuration subsystem. Phase 1 returns
 * {@code {"status":"healthy","skipped":[]}} unconditionally — the invalid-entity sibling store
 * that populates {@code skipped} ships in slice 2S.9, alongside the {@code dial_config_*}
 * Prometheus metrics referenced in the slice register row.
 */
public class AdminHealthConfigController implements Controller {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;

    public AdminHealthConfigController(ProxyContext context, ConfigAuthorizationService authorizationService) {
        this.context = context;
        this.authorizationService = authorizationService;
    }

    @Override
    public Future<?> handle() throws Exception {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.put("status", "healthy");
        body.putArray("skipped");
        context.respond(HttpStatus.OK, body);
        return Future.succeededFuture();
    }
}
