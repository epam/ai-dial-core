package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Resource;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.storage.ResourceType;
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

    public ResourceController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.vertx = proxy.getVertx();
        this.service = proxy.getResourceService();
    }

    @Override
    protected Future<?> handle(ResourceDescription descriptor) {
        if (context.getRequest().method() == HttpMethod.GET) {
            return descriptor.isFolder() ? list(descriptor) : get(descriptor);
        }

        if (context.getRequest().method() == HttpMethod.PUT) {
            return put(descriptor);
        }

        if (context.getRequest().method() == HttpMethod.DELETE) {
            return delete(descriptor);
        }

        return context.respond(HttpStatus.BAD_GATEWAY);
    }

    private Future<?> list(ResourceDescription descriptor) {
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

        return vertx.executeBlocking(() -> service.list(descriptor, token, limit))
                .onSuccess(result -> {
                    if (result == null) {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getEncryptedPath());
                    } else {
                        result.getResources().forEach(resource -> encrypt(descriptor, resource));
                        context.respond(HttpStatus.OK, result);
                    }
                })
                .onFailure(error -> {
                    log.warn("Can't list resource: {}", descriptor.getEncryptedPath(), error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                });
    }

    private Future<?> get(ResourceDescription descriptor) {
        return vertx.executeBlocking(() -> service.get(descriptor))
                .onSuccess(resource -> {
                    if (resource == null) {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getEncryptedPath());
                    } else {
                        context.respond(HttpStatus.OK, resource.getBody());
                    }
                })
                .onFailure(error -> {
                    log.warn("Can't get resource: {}", descriptor.getEncryptedPath(), error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                });
    }

    private Future<?> put(ResourceDescription descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Folder not allowed: " + descriptor.getEncryptedPath());
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
                    return vertx.executeBlocking(() -> service.put(descriptor, body));
                })
                .onSuccess((ok) -> context.respond(HttpStatus.OK))
                .onFailure(error -> {
                    if (error instanceof HttpException exception) {
                        context.respond(exception.getStatus(), exception.getMessage());
                    } else {
                        log.warn("Can't put resource: {}", descriptor.getEncryptedPath(), error);
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                });
    }

    private Future<?> delete(ResourceDescription descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Folder not allowed: " + descriptor.getEncryptedPath());
        }

        return vertx.executeBlocking(() -> service.delete(descriptor))
                .onSuccess(deleted -> {
                    if (deleted) {
                        context.respond(HttpStatus.OK);
                    } else {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getEncryptedPath());
                    }
                })
                .onFailure(error -> {
                    log.warn("Can't delete resource: {}", descriptor.getEncryptedPath(), error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                });
    }

    private static void encrypt(ResourceDescription descriptor, Resource resource) {
        String decrypted = resource.getPath();
        ResourceType type = descriptor.getType();
        String location = descriptor.getBucketLocation();
        String name = descriptor.getBucketName();
        String relativePath = decrypted.substring(location.length() + type.getFolder().length() + 1);
        String encrypted = ResourceDescription.fromDecoded(type, name, location, relativePath).getEncryptedPath();
        resource.setPath(encrypted);
    }
}