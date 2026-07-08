package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.data.AdminApplyRequest;
import com.epam.aidial.core.server.data.AdminManifest;
import com.epam.aidial.core.server.data.AdminValidateResponse;
import com.epam.aidial.core.server.data.ValidationResult;
import com.epam.aidial.core.server.data.ValidationStatus;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Admin-only configuration-validation endpoint at {@code POST /v1/admin/validate}.
 * Phase 4 scope (design 03 §6) is multi-entity, batch-aware with {@code precheck} semantics —
 * predicts the outcome of the matching {@code POST /v1/admin/apply} payload without mutation.
 * Shares the validation engine with {@link AdminApplyController} (promoted statics) so
 * {@code precheck=true} guarantees apply-parity: if validate returns 200, apply would not
 * unit-reject; if validate returns 422, apply with {@code precheck=true} would also 422.
 */
public class AdminValidateController implements Controller {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final MergedConfigStore mergedConfigStore;
    private final AsyncTaskExecutor taskExecutor;

    public AdminValidateController(ProxyContext context,
                                   ConfigAuthorizationService authorizationService,
                                   MergedConfigStore mergedConfigStore,
                                   AsyncTaskExecutor taskExecutor) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.mergedConfigStore = mergedConfigStore;
        this.taskExecutor = taskExecutor;
    }

    @Override
    @ApiOperation(
            method = "POST",
            path = "/v1/admin/validate",
            operationId = "validateConfigManifests",
            tags = {"Admin"},
            requestBody = @ApiSchema(implementation = AdminApplyRequest.class),
            responses = {
                    @ApiResponse(code = 200, description = "Validation successful", body = @ApiSchema(implementation = AdminValidateResponse.class)),
                    @ApiResponse(code = 422, description = "Validation failed", body = @ApiSchema(implementation = AdminValidateResponse.class))
            },
            responseProfile = ResponseProfile.ADMIN_BATCH,
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
            String text = body == null ? "" : body.toString(StandardCharsets.UTF_8);
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
                context.respond(HttpStatus.BAD_REQUEST, "Bundle kind is not allowed in /v1/admin/validate");
                return;
            }
        }

        taskExecutor.submit(() -> validateBatch(precheck, entries))
                .onSuccess(result -> context.respond(result.status(), result.body()))
                .onFailure(error -> {
                    if (error instanceof HttpException ex) {
                        context.respond(ex);
                    } else {
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                    }
                });
    }

    private ValidateResponse validateBatch(boolean precheck,
                                           List<AdminManifest> rawEntries) {
        List<AdminManifest> entries = new ArrayList<>(rawEntries);
        entries.sort(Comparator.comparingInt(
                e -> AdminApplyController.DEPENDENCY_ORDER.getOrDefault(e.kind(), 99)));

        boolean softValidation = mergedConfigStore.isSoftValidation();
        Config scratch = AdminApplyController.newScratch(mergedConfigStore);
        List<ValidationResult> results = new ArrayList<>();
        boolean anyFailure = false;

        for (AdminManifest entry : entries) {
            String entityId = AdminApplyController.entityId(entry);
            String error = null;
            // Unknown kinds FAIL on both surfaces: validateOnly returns FAILED (apply precheck
            // rejects the batch with 422), so this explicit branch is redundant but kept as a
            // defensive guard. Bundle is already rejected at the envelope-parse level (per 4S.0).
            if (!AdminApplyController.KIND_URL_SEGMENT.containsKey(entry.kind())) {
                error = "Unknown kind: " + entry.kind();
            } else {
                ValidationResult validation =
                        AdminApplyController.validateOnly(entry, scratch, softValidation);
                if (!ValidationStatus.VALID.equals(validation.status())) {
                    error = validation.error();
                }
            }
            if (error == null) {
                AdminApplyController.mutateScratch(scratch, entry);
                results.add(new ValidationResult(entityId, ValidationStatus.VALID, null));
            } else {
                anyFailure = true;
                results.add(new ValidationResult(entityId, ValidationStatus.FAILED, error));
            }
        }

        if (precheck && anyFailure) {
            List<ValidationResult> finalResults = new ArrayList<>(results.size());
            for (ValidationResult r : results) {
                if (ValidationStatus.VALID.equals(r.status())) {
                    finalResults.add(new ValidationResult(r.entityId(), ValidationStatus.SKIPPED, null));
                } else {
                    finalResults.add(r);
                }
            }
            return buildValidateResponse(HttpStatus.UNPROCESSABLE_ENTITY, finalResults);
        }
        return buildValidateResponse(HttpStatus.OK, results);
    }

    private ValidateResponse buildValidateResponse(HttpStatus status,
                                                   List<ValidationResult> results) {
        int valid = 0;
        int failed = 0;
        for (ValidationResult r : results) {
            if (ValidationStatus.VALID.equals(r.status())) {
                valid++;
            } else if (ValidationStatus.FAILED.equals(r.status())) {
                failed++;
            }
        }
        return new ValidateResponse(status, new AdminValidateResponse(valid, failed, results));
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }

    private record ValidateResponse(HttpStatus status, AdminValidateResponse body) {}
}