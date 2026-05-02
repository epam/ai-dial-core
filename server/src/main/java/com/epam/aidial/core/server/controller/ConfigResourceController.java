package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.security.EntityBucketBinding;
import com.epam.aidial.core.server.security.Operation;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;

import java.util.Map;
import java.util.TreeMap;

/**
 * Stub controller for the {@code /v1/{type}/{bucket}/{path}} CONFIG_RESOURCE route — gates on
 * the {@link EntityBucketBinding} allowlist and {@link ConfigAuthorizationService}, then returns
 * 405. Real handlers replace the 405 response in subsequent slices.
 *
 * <p>Returning 405 (not 404) on the post-gate path keeps binding-mismatch responses
 * indistinguishable from "entity not found" while making "route matched, no handler" visible.
 */
public class ConfigResourceController implements Controller {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final String entityType;
    private final String bucket;
    private final String path;

    public ConfigResourceController(ProxyContext context,
                                    ConfigAuthorizationService authorizationService,
                                    String entityType,
                                    String bucket,
                                    String path) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.entityType = entityType;
        this.bucket = bucket;
        this.path = path;
    }

    @Override
    public Future<?> handle() throws Exception {
        if (!EntityBucketBinding.isAllowed(entityType, bucket)) {
            // Body-less 404 — must be indistinguishable from "entity not found" so an unauthenticated
            // probe cannot tell from the response whether the (type, bucket) pair is invalid or
            // merely empty. See 04-security-and-audit.md §1.2.
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }

        HttpMethod method = context.getRequest().method();
        Operation operation = (method == HttpMethod.GET || method == HttpMethod.HEAD)
                ? Operation.READ
                : Operation.WRITE;
        if (!authorizationService.isAuthorized(context, entityType, path, bucket, operation)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }

        if (method == HttpMethod.GET && "models".equals(entityType)) {
            return handleModelGet(path);
        }

        context.respond(HttpStatus.METHOD_NOT_ALLOWED, "Not implemented");
        return Future.succeededFuture();
    }

    private Future<?> handleModelGet(String name) {
        if (name == null || name.isEmpty()) {
            return handleModelList();
        }
        Model model = context.getConfig().getModels().get(name);
        if (model == null) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        context.respond(HttpStatus.OK, projectModelItem(name, model, authorizationService.isAdmin(context)));
        return Future.succeededFuture();
    }

    private Future<?> handleModelList() {
        // Phase 1 returns the full in-memory snapshot — limit is shape-validated only, cursor is
        // accepted-and-ignored (design 03 §4 forward-compat: hasMore: false always, nextCursor absent).
        if (!isLimitValid()) {
            context.respond(HttpStatus.BAD_REQUEST, "Invalid 'limit' query parameter");
            return Future.succeededFuture();
        }
        boolean admin = authorizationService.isAdmin(context);
        ArrayNode items = ProxyUtil.MAPPER.createArrayNode();
        for (Map.Entry<String, Model> entry : new TreeMap<>(context.getConfig().getModels()).entrySet()) {
            items.add(projectModelItem(entry.getKey(), entry.getValue(), admin));
        }
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.put("entityType", entityType);
        body.put("bucket", bucket);
        body.set("items", items);
        body.put("hasMore", false);
        context.respond(HttpStatus.OK, body);
        return Future.succeededFuture();
    }

    private ObjectNode projectModelItem(String name, Model model, boolean admin) {
        ObjectNode node = ProxyUtil.MAPPER.valueToTree(model);
        node.put("name", name);
        node.put("status", "valid");
        if (admin) {
            node.put("source", "file");
        }
        return node;
    }

    /** Phase 1 validates limit shape only — accepts absent or any positive integer (clamping ships in Phase 2). */
    private boolean isLimitValid() {
        String raw = context.getRequest().getParam("limit");
        if (raw == null) {
            return true;
        }
        try {
            return Integer.parseInt(raw) >= 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
