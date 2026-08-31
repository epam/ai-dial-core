package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.data.AdminApplyRequest;
import com.epam.aidial.core.server.data.AdminApplyResponse;
import com.epam.aidial.core.server.data.AdminApplyResult;
import com.epam.aidial.core.server.data.AdminApplyStatus;
import com.epam.aidial.core.server.data.AdminManifest;
import com.epam.aidial.core.server.data.ValidationResult;
import com.epam.aidial.core.server.data.ValidationStatus;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.config.ConfigApplyService;
import com.epam.aidial.core.server.service.config.ConfigManifestSupport;
import com.epam.aidial.core.server.service.config.ConfigValidationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class AdminApplyController {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final AsyncTaskExecutor taskExecutor;
    private final LockService lockService;
    private final MergedConfigStore mergedConfigStore;
    private final ConfigApplyService applyService;
    private final ConfigValidationService validationService;

    public AdminApplyController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.authorizationService = proxy.getConfigAuthService();
        this.taskExecutor = proxy.getTaskExecutor();
        this.lockService = proxy.getLockService();
        this.mergedConfigStore = (MergedConfigStore) proxy.getConfigStore();
        this.applyService = proxy.getConfigApplyService();
        this.validationService = proxy.getConfigValidationService();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/admin/apply",
            operationId = "applyConfigManifests",
            tags = {"Admin"},
            requestBody = @ApiSchema(implementation = AdminApplyRequest.class),
            responses = {
                    @ApiResponse(code = 200, description = "Application successful", body = @ApiSchema(implementation = AdminApplyResponse.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 422, description = "Precheck failed", body = @ApiSchema(implementation = AdminApplyResponse.class)),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> handle() {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }
        context.getRequest().body()
                .onSuccess(this::process)
                .onFailure(error -> context.respond(HttpStatus.BAD_REQUEST,
                        "Failed to read request body: " + error.getMessage()));
        return Future.succeededFuture();
    }

    private void process(Buffer body) {
        AdminApplyRequest request;
        try {
            String text = body.toString(StandardCharsets.UTF_8);
            request = ProxyUtil.MAPPER.readValue(text.isEmpty() ? "{}" : text, AdminApplyRequest.class);
        } catch (JsonProcessingException e) {
            // getOriginalMessage() echoes the offending token verbatim, which can leak submitted
            // secrets back into responses and logs — surface only the parse location.
            context.respond(HttpStatus.BAD_REQUEST, "Invalid JSON at " + locationOf(e));
            return;
        }

        if (request.manifests() == null) {
            context.respond(HttpStatus.BAD_REQUEST, "Missing or invalid 'manifests' array");
            return;
        }

        // Treat missing precheck value as true
        boolean precheck = request.precheck() == null || request.precheck();

        List<AdminManifest> entries = request.manifests();
        for (int i = 0; i < entries.size(); i++) {
            AdminManifest entry = entries.get(i);
            if (entry.kind() == null) {
                context.respond(HttpStatus.BAD_REQUEST, "manifests[" + i + "].kind must be a string");
                return;
            }
            if ("Bundle".equals(entry.kind())) {
                context.respond(HttpStatus.BAD_REQUEST, "Bundle kind is not allowed in /v1/admin/apply");
                return;
            }
        }

        taskExecutor.submit(() -> lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS,
                () -> applyBatch(precheck, entries)))
                .onSuccess(result -> context.respond(result.status(), result.body()))
                .onFailure(error -> {
                    if (error instanceof HttpException ex) {
                        context.respond(ex);
                    } else if (error instanceof IllegalArgumentException ex) {
                        context.respond(HttpStatus.BAD_REQUEST, ex.getMessage());
                    } else {
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                    }
                });
    }

    private ApplyResponse applyBatch(boolean precheck, List<AdminManifest> rawEntries) {
        List<AdminManifest> entries = new ArrayList<>(rawEntries);
        entries.sort(ConfigManifestSupport.DEPENDENCY_ORDER_COMPARATOR);

        Config scratch = ConfigManifestSupport.newScratch(mergedConfigStore);

        if (precheck) {
            List<ConfigApplyService.EntityResult> precheckResults = new ArrayList<>();
            boolean anyFailure = false;
            for (AdminManifest entry : entries) {
                ValidationResult result = validationService.validateOnly(entry, scratch);
                if (!ValidationStatus.VALID.equals(result.status())) {
                    anyFailure = true;
                    // Mirror /v1/admin/validate: the offending entry stays FAILED (carrying its
                    // error); only the valid siblings collapse to "skipped" below.
                    precheckResults.add(new ConfigApplyService.EntityResult(result.entityId(), AdminApplyStatus.FAILED, result.error()));
                } else {
                    // Mutate scratch so subsequent precheck entries see prior ones — even though we
                    // aren't writing yet, reference resolution depends on the cumulative scratch.
                    ConfigManifestSupport.mutateScratch(scratch, entry);
                    precheckResults.add(new ConfigApplyService.EntityResult(result.entityId(), AdminApplyStatus.SKIPPED, null));
                }
            }
            if (anyFailure) {
                return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, precheckResults);
            }
            // Precheck passed — wipe and re-run as real writes.
            scratch = ConfigManifestSupport.newScratch(mergedConfigStore);
        }

        return buildResponse(HttpStatus.OK, applyService.applyEntries(entries, scratch));
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }

    private ApplyResponse buildResponse(HttpStatus status, List<ConfigApplyService.EntityResult> results) {
        int applied = 0;
        int failed = 0;

        List<AdminApplyResult> responseResults = new ArrayList<>();

        for (ConfigApplyService.EntityResult r : results) {
            if (r.status() == AdminApplyStatus.APPLIED || r.status() == AdminApplyStatus.APPLIED_INVALID) {
                applied++;
            } else if (r.status() == AdminApplyStatus.FAILED) {
                failed++;
            }
            responseResults.add(
                    new AdminApplyResult(
                            r.entityId(),
                            r.status(),
                            r.error()
                    )
            );
        }
        return new ApplyResponse(status, new AdminApplyResponse(applied, failed, responseResults));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ApplyResponse(HttpStatus status, AdminApplyResponse body) {}
}
