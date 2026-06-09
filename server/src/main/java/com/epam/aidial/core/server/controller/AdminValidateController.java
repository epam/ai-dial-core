package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        JsonNode envelope;
        try {
            String text = body == null ? "" : body.toString(StandardCharsets.UTF_8);
            envelope = ProxyUtil.MAPPER.readTree(text.isEmpty() ? "{}" : text);
        } catch (JsonProcessingException e) {
            // getOriginalMessage() echoes the offending token verbatim, which can leak submitted
            // secrets back into responses and logs — surface only the parse location.
            context.respond(HttpStatus.BAD_REQUEST, "Invalid JSON at " + locationOf(e));
            return;
        }
        if (!envelope.isObject()) {
            context.respond(HttpStatus.BAD_REQUEST, "Request body must be a JSON object");
            return;
        }
        JsonNode manifestsNode = envelope.get("manifests");
        if (manifestsNode == null || !manifestsNode.isArray()) {
            context.respond(HttpStatus.BAD_REQUEST, "Missing or invalid 'manifests' array");
            return;
        }
        boolean precheck = !envelope.has("precheck") || envelope.get("precheck").asBoolean(true);

        List<AdminApplyController.ManifestEntry> entries = new ArrayList<>();
        for (int i = 0; i < manifestsNode.size(); i++) {
            JsonNode entryNode = manifestsNode.get(i);
            if (!entryNode.isObject()) {
                context.respond(HttpStatus.BAD_REQUEST, "manifests[" + i + "] must be a JSON object");
                return;
            }
            JsonNode kindNode = entryNode.get("kind");
            if (kindNode == null || !kindNode.isTextual()) {
                context.respond(HttpStatus.BAD_REQUEST, "manifests[" + i + "].kind must be a string");
                return;
            }
            String kind = kindNode.asText();
            if ("Bundle".equals(kind)) {
                context.respond(HttpStatus.BAD_REQUEST, "Bundle kind is not allowed in /v1/admin/validate");
                return;
            }
            String name = entryNode.hasNonNull("name") ? entryNode.get("name").asText() : null;
            JsonNode spec = entryNode.get("spec");
            entries.add(new AdminApplyController.ManifestEntry(kind, name, spec));
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
                                           List<AdminApplyController.ManifestEntry> rawEntries) {
        List<AdminApplyController.ManifestEntry> entries = new ArrayList<>(rawEntries);
        entries.sort(Comparator.comparingInt(
                e -> AdminApplyController.DEPENDENCY_ORDER.getOrDefault(e.kind(), 99)));

        boolean softValidation = mergedConfigStore.isSoftValidation();
        Config scratch = AdminApplyController.newScratch(mergedConfigStore);
        List<AdminApplyController.EntityResult> results = new ArrayList<>();
        boolean anyFailure = false;

        for (AdminApplyController.ManifestEntry entry : entries) {
            String entityId = AdminApplyController.entityId(entry);
            String error = null;
            // Unknown kinds FAIL on both surfaces: validateOnly returns FAILED (apply precheck
            // rejects the batch with 422), so this explicit branch is redundant but kept as a
            // defensive guard. Bundle is already rejected at the envelope-parse level (per 4S.0).
            if (!AdminApplyController.KIND_URL_SEGMENT.containsKey(entry.kind())) {
                error = "Unknown kind: " + entry.kind();
            } else {
                AdminApplyController.EntityResult validation =
                        AdminApplyController.validateOnly(entry, scratch, softValidation);
                if (!"valid".equals(validation.status())) {
                    error = validation.error();
                }
            }
            if (error == null) {
                AdminApplyController.mutateScratch(scratch, entry);
                results.add(new AdminApplyController.EntityResult(entityId, "valid", null));
            } else {
                anyFailure = true;
                results.add(new AdminApplyController.EntityResult(entityId, "FAILED", error));
            }
        }

        if (precheck && anyFailure) {
            List<AdminApplyController.EntityResult> finalResults = new ArrayList<>(results.size());
            for (AdminApplyController.EntityResult r : results) {
                if ("valid".equals(r.status())) {
                    finalResults.add(new AdminApplyController.EntityResult(r.entityId(), "skipped", null));
                } else {
                    finalResults.add(r);
                }
            }
            return buildValidateResponse(HttpStatus.UNPROCESSABLE_ENTITY, finalResults);
        }
        return buildValidateResponse(HttpStatus.OK, results);
    }

    private ValidateResponse buildValidateResponse(HttpStatus status,
                                                   List<AdminApplyController.EntityResult> results) {
        int valid = 0;
        int failed = 0;
        for (AdminApplyController.EntityResult r : results) {
            if ("valid".equals(r.status())) {
                valid++;
            } else if ("FAILED".equals(r.status())) {
                failed++;
            }
        }
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.put("valid", valid);
        body.put("failed", failed);
        ArrayNode arr = body.putArray("results");
        for (AdminApplyController.EntityResult r : results) {
            ObjectNode n = arr.addObject();
            n.put("entityId", r.entityId());
            n.put("status", r.status());
            if (r.error() != null) {
                n.put("error", r.error());
            }
        }
        return new ValidateResponse(status, body);
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }

    private record ValidateResponse(HttpStatus status, ObjectNode body) {}
}
