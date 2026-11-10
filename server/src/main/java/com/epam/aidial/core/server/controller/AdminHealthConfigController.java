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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin-only health endpoint for the configuration subsystem. Reports
 * {@code {"status":"ok"|"degraded","skipped":[{"id":..., "reason":...}]}}
 * computed from {@link MergedConfigStore#getInvalidEntities()} (design 02 §4.1), plus the aliases, of the config
 * file ({@code aliases/<name>}) and stored ({@code aliases/public/<name>}, {@code aliases/platform/<name>}), whose target is missing or no longer
 * fits. Resource targets and the stored aliases are read for it, on the task executor.
 */
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
        AliasService aliasService = context.getProxy().getDeploymentService().getAliasService();
        // resource targets and the stored aliases are read from storage, so on the task executor
        context.getProxy().getTaskExecutor().submit(() -> {
            List<SkippedEntity> broken = new ArrayList<>();
            for (Alias alias : config.getAliases().values()) {
                addIfBroken(broken, aliasService, alias, ResourceTypes.ALIAS.group() + "/" + alias.getName(), config);
            }
            // a stored alias is named by its url
            for (Alias alias : aliasService.listStoredAliases()) {
                addIfBroken(broken, aliasService, alias, alias.getName(), config);
            }
            return broken;
        }).onSuccess(broken -> {
            skipped.addAll(broken);
            respond(skipped);
        }).onFailure(error -> context.respond(error, "Can't check the aliases"));
        return Future.succeededFuture();
    }

    /**
     * Adds {@code alias} to {@code skipped} when its target is missing or no longer fits: a config target is looked up
     * in {@code config}, a resource target is read.
     */
    private void addIfBroken(List<SkippedEntity> skipped, AliasService aliasService, Alias alias, String id, Config config) {
        if (AliasTargets.isResourcePath(alias.getTarget().getPath())) {
            AliasService.TargetProblem problem = aliasService.checkTarget(context, alias);
            if (problem != null) {
                skipped.add(new SkippedEntity(id, problem == AliasService.TargetProblem.MISSING
                        ? REASON_ALIAS_TARGET_MISSING : REASON_ALIAS_TARGET_INCOMPATIBLE));
            }
        } else if (AliasTargets.isTargetMissing(alias, config)) {
            skipped.add(new SkippedEntity(id, REASON_ALIAS_TARGET_MISSING));
        } else if (AliasTargets.isTargetIncompatible(alias, config)) {
            skipped.add(new SkippedEntity(id, REASON_ALIAS_TARGET_INCOMPATIBLE));
        }
    }

    private void respond(List<SkippedEntity> skipped) {
        String status = skipped.isEmpty() ? "ok" : "degraded";
        context.respond(HttpStatus.OK, new HealthResponse(status, skipped));
    }
}