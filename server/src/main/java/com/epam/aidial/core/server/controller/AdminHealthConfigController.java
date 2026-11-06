package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.alias.AliasService;
import com.epam.aidial.core.server.alias.AliasTargets;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.data.HealthResponse;
import com.epam.aidial.core.server.data.SkippedEntity;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin-only health endpoint for the configuration subsystem. Reports
 * {@code {"status":"ok"|"degraded","skipped":[{"id":..., "reason":...}]}}
 * computed from {@link MergedConfigStore#getInvalidEntities()} (design 02 §4.1), plus the aliases whose target is
 * missing or no longer fits. A resource target is read for it, on the task executor.
 */
@Slf4j
public class AdminHealthConfigController implements Controller {

    // an alias whose target is missing loads, but its calls answer 503 until the target is back
    static final String REASON_ALIAS_TARGET_MISSING = "alias_target_missing";
    // an alias whose target no longer fits its type, after a write of the target
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
        if (config == null) {
            respond(skipped);
            return Future.succeededFuture();
        }
        List<Alias> resourceAliases = new ArrayList<>();
        for (Alias alias : config.getAliases().values()) {
            if (AliasTargets.isResourcePath(alias.getTarget().getPath())) {
                resourceAliases.add(alias);
            } else if (AliasTargets.isTargetMissing(alias, config)) {
                skipped.add(aliasEntry(alias, REASON_ALIAS_TARGET_MISSING));
            } else if (AliasTargets.isTargetIncompatible(alias, config)) {
                skipped.add(aliasEntry(alias, REASON_ALIAS_TARGET_INCOMPATIBLE));
            }
        }
        if (resourceAliases.isEmpty()) {
            respond(skipped);
            return Future.succeededFuture();
        }
        AliasService aliasService = context.getProxy().getDeploymentService().getAliasService();
        context.getProxy().getTaskExecutor().submit(() -> {
            List<SkippedEntity> broken = new ArrayList<>();
            for (Alias alias : resourceAliases) {
                AliasService.TargetProblem problem = aliasService.checkTarget(context, alias);
                if (problem != null) {
                    broken.add(aliasEntry(alias, problem == AliasService.TargetProblem.MISSING
                            ? REASON_ALIAS_TARGET_MISSING : REASON_ALIAS_TARGET_INCOMPATIBLE));
                }
            }
            return broken;
        }).onSuccess(broken -> {
            skipped.addAll(broken);
            respond(skipped);
        }).onFailure(error -> {
            log.error("Can't check the resource targets of aliases", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Can't check the resource targets of aliases");
        });
        return Future.succeededFuture();
    }

    private static SkippedEntity aliasEntry(Alias alias, String reason) {
        return new SkippedEntity(ResourceTypes.ALIAS.group() + "/" + alias.getName(), reason);
    }

    private void respond(List<SkippedEntity> skipped) {
        String status = skipped.isEmpty() ? "ok" : "degraded";
        context.respond(HttpStatus.OK, new HealthResponse(status, skipped));
    }
}