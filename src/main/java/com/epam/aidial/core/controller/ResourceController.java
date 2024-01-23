package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
@SuppressWarnings("checkstyle:Indentation")
public class ResourceController extends AccessControlBaseController {

    private final Vertx vertx;
    private final ResourceService service;
    private final boolean metadata;

    public ResourceController(Proxy proxy, ProxyContext context, boolean metadata) {
        super(proxy, context, true);
        this.vertx = proxy.getVertx();
        this.service = proxy.getResourceService();
        this.metadata = metadata;
    }

    @Override
    protected Future<?> handle(ResourceDescription descriptor) {
        if (context.getRequest().method() == HttpMethod.GET) {
            return metadata ? getMetadata(descriptor) : getResource(descriptor);
        }

        if (context.getRequest().method() == HttpMethod.PUT) {
            return putResource(descriptor);
        }

        if (context.getRequest().method() == HttpMethod.DELETE) {
            return deleteResource(descriptor);
        }

        return context.respond(HttpStatus.BAD_GATEWAY, "No route");
    }

    private Future<?> getMetadata(ResourceDescription descriptor) {
        String token;
        int limit;

        try {
            token = context.getRequest().getParam("token");
            limit = Integer.parseInt(context.getRequest().getParam("limit", "100"));
            if (limit < 0 || limit > 1000) {
                throw new IllegalArgumentException("Limit is out of allowed range");
            }
        } catch (Throwable error) {
            return context.respond(HttpStatus.BAD_REQUEST, "Bad query parameters. Limit must be in [0, 1000] range");
        }

        return vertx.executeBlocking(() -> service.getMetadata(descriptor, token, limit))
                .onSuccess(result -> {
                    if (result == null) {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
                    } else {
                        context.respond(HttpStatus.OK, result);
                    }
                })
                .onFailure(error -> {
                    log.warn("Can't list resource: {}", descriptor.getUrl(), error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                });
    }

    private Future<?> getResource(ResourceDescription descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        return vertx.executeBlocking(() -> service.getResource(descriptor))
                .onSuccess(body -> {
                    if (body == null) {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
                    } else {
                        context.respond(HttpStatus.OK, body);
                    }
                })
                .onFailure(error -> {
                    log.warn("Can't get resource: {}", descriptor.getUrl(), error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                });
    }

    private Future<?> putResource(ResourceDescription descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        int contentLength = ProxyUtil.contentLength(context.getRequest(), 0);
        int contentLimit = service.getMaxSize();

        if (contentLength > contentLimit) {
            String message = "Resource size: %s exceeds max limit: %s".formatted(contentLength, contentLimit);
            return context.respond(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
        }

        return context.getRequest().body().compose(bytes -> {
                    if (bytes.length() > contentLimit) {
                        String message = "Resource size: %s exceeds max limit: %s".formatted(bytes.length(), contentLimit);
                        throw new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
                    }

                    String body = bytes.toString(StandardCharsets.UTF_8);
                    return vertx.executeBlocking(() -> service.putResource(descriptor, body));
                })
                .onSuccess((ok) -> context.respond(HttpStatus.OK))
                .onFailure(error -> {
                    if (error instanceof HttpException exception) {
                        context.respond(exception.getStatus(), exception.getMessage());
                    } else {
                        log.warn("Can't put resource: {}", descriptor.getUrl(), error);
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                });
    }

    private Future<?> deleteResource(ResourceDescription descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        return vertx.executeBlocking(() -> service.deleteResource(descriptor))
                .onSuccess(deleted -> {
                    if (deleted) {
                        context.respond(HttpStatus.OK);
                    } else {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
                    }
                })
                .onFailure(error -> {
                    log.warn("Can't delete resource: {}", descriptor.getUrl(), error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                });
    }
}