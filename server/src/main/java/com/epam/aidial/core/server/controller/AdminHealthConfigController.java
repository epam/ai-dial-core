package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

import java.util.Map;

/**
 * Admin-only health endpoint for the configuration subsystem. Reports
 * {@code {"status":"ok"|"degraded","skipped":[{"id":..., "reason":...}]}}
 * computed from {@link MergedConfigStore#getInvalidEntities()} (design 02 §4.1).
 */
public class AdminHealthConfigController implements Controller {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final MergedConfigStore mergedConfigStore;

    public AdminHealthConfigController(ProxyContext context,
                                       ConfigAuthorizationService authorizationService,
                                       MergedConfigStore mergedConfigStore) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.mergedConfigStore = mergedConfigStore;
    }

    @Override
    public Future<?> handle() throws Exception {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        ArrayNode skipped = body.putArray("skipped");
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities = mergedConfigStore.getInvalidEntities();
        for (Map<String, InvalidEntityRecord> perType : invalidEntities.values()) {
            for (InvalidEntityRecord record : perType.values()) {
                ObjectNode entry = skipped.addObject();
                entry.put("id", record.getCanonicalId());
                entry.put("reason", record.getReason());
            }
        }
        body.put("status", skipped.isEmpty() ? "ok" : "degraded");
        context.respond(HttpStatus.OK, body);
        return Future.succeededFuture();
    }
}
