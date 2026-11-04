package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.alias.AliasTargets;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.data.HealthResponse;
import com.epam.aidial.core.server.data.SkippedEntity;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin-only health endpoint for the configuration subsystem. Reports
 * {@code {"status":"ok"|"degraded","skipped":[{"id":..., "reason":...}]}}
 * computed from {@link MergedConfigStore#getInvalidEntities()} (design 02 §4.1), plus the aliases whose config
 * target is missing or no longer fits.
 */
public class AdminHealthConfigController implements Controller {

    // an alias whose config target is missing loads, but its calls answer 503 until the target is back
    static final String REASON_ALIAS_TARGET_MISSING = "alias_target_missing";
    // an alias whose config target no longer fits its type, after an API write of the target
    static final String REASON_ALIAS_TARGET_INCOMPATIBLE = "alias_target_incompatible";

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
    @ApiOperation(
            method = "GET",
            path = "/v1/admin/health/config",
            operationId = "getConfigHealth",
            tags = {"Admin"},
            responses = {
                    @ApiResponse(code = 200, description = "Configuration health status", body = @ApiSchema(implementation = HealthResponse.class)),
                    @ApiResponse(code = 403)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> handle() throws Exception {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }
        List<SkippedEntity> skipped = new ArrayList<>();
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities = mergedConfigStore.getInvalidEntities();
        for (Map<String, InvalidEntityRecord> perType : invalidEntities.values()) {
            for (InvalidEntityRecord record : perType.values()) {
                skipped.add(new SkippedEntity(record.getCanonicalId(), record.getReason()));
            }
        }
        Config config = context.getConfig();
        if (config != null) {
            for (Alias alias : config.getAliases().values()) {
                if (AliasTargets.isTargetMissing(alias, config)) {
                    skipped.add(new SkippedEntity(ResourceTypes.ALIAS.group() + "/" + alias.getName(), REASON_ALIAS_TARGET_MISSING));
                } else if (AliasTargets.isTargetIncompatible(alias, config)) {
                    skipped.add(new SkippedEntity(ResourceTypes.ALIAS.group() + "/" + alias.getName(), REASON_ALIAS_TARGET_INCOMPATIBLE));
                }
            }
        }
        String status = skipped.isEmpty() ? "ok" : "degraded";
        HealthResponse response = new HealthResponse(status, skipped);
        context.respond(HttpStatus.OK, response);
        return Future.succeededFuture();
    }
}