package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.security.EntityBucketBinding;
import com.epam.aidial.core.server.security.Operation;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;

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

        context.respond(HttpStatus.METHOD_NOT_ALLOWED, "Not implemented");
        return Future.succeededFuture();
    }
}
