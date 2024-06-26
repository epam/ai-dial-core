package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Conversation;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.Prompt;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.service.InvitationService;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.PermissionsFetcher;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
@SuppressWarnings("checkstyle:Indentation")
public class ResourceController extends AccessControlBaseController {

    private final Vertx vertx;
    private final ResourceService service;
    private final ShareService shareService;
    private final LockService lockService;
    private final InvitationService invitationService;
    private final boolean metadata;
    private final AccessService accessService;

    public ResourceController(Proxy proxy, ProxyContext context, boolean metadata) {
        // PUT and DELETE require write access, GET - read
        super(proxy, context, !HttpMethod.GET.equals(context.getRequest().method()));
        this.vertx = proxy.getVertx();
        this.service = proxy.getResourceService();
        this.shareService = proxy.getShareService();
        this.accessService = proxy.getAccessService();
        this.lockService = proxy.getLockService();
        this.invitationService = proxy.getInvitationService();
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

    private String getContentType() {
        String acceptType = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return acceptType != null && metadata && acceptType.contains(MetadataBase.MIME_TYPE)
                ? MetadataBase.MIME_TYPE
                : "application/json";
    }

    private Future<?> getMetadata(ResourceDescription descriptor) {
        String token;
        int limit;
        boolean recursive;

        try {
            token = context.getRequest().getParam("token");
            limit = Integer.parseInt(context.getRequest().getParam("limit", "100"));
            recursive = Boolean.parseBoolean(context.getRequest().getParam("recursive", "false"));
            if (limit < 0 || limit > 1000) {
                throw new IllegalArgumentException("Limit is out of allowed range");
            }
        } catch (Throwable error) {
            return context.respond(HttpStatus.BAD_REQUEST, "Bad query parameters. Limit must be in [0, 1000] range. Recursive must be true/false");
        }

        PermissionsFetcher permissionsFetcher = PermissionsFetcher.of(context, accessService);
        return vertx.executeBlocking(() -> service.getMetadata(
                descriptor, permissionsFetcher, token, limit, recursive), false)
                .onSuccess(result -> {
                    if (result == null) {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
                    } else {
                        proxy.getAccessService().filterForbidden(context, descriptor, result);
                        context.respond(HttpStatus.OK, getContentType(), result);
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

        return vertx.executeBlocking(() -> service.getResource(descriptor), false)
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

        if (!ResourceDescription.isValidResourcePath(descriptor)) {
            return context.respond(HttpStatus.BAD_REQUEST, "Resource name and/or parent folders must not end with .(dot)");
        }

        int contentLength = ProxyUtil.contentLength(context.getRequest(), 0);
        int contentLimit = service.getMaxSize();

        if (contentLength > contentLimit) {
            String message = "Resource size: %s exceeds max limit: %s".formatted(contentLength, contentLimit);
            return context.respond(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
        }

        String ifNoneMatch = context.getRequest().getHeader(HttpHeaders.IF_NONE_MATCH);
        boolean overwrite = (ifNoneMatch == null);

        if (ifNoneMatch != null && !ifNoneMatch.equals("*")) {
            return context.respond(HttpStatus.BAD_REQUEST, "only header if-none-match=* is supported");
        }

        return context.getRequest().body().compose(bytes -> {
                    if (bytes.length() > contentLimit) {
                        String message = "Resource size: %s exceeds max limit: %s".formatted(bytes.length(), contentLimit);
                        throw new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
                    }

                    String body = bytes.toString(StandardCharsets.UTF_8);

                    ResourceType resourceType = descriptor.getType();
                    switch (resourceType) {
                        case PROMPT -> ProxyUtil.convertToObject(body, Prompt.class);
                        case CONVERSATION -> ProxyUtil.convertToObject(body, Conversation.class);
                        default -> throw new IllegalArgumentException("Unsupported resource type " + resourceType);
                    }

                    return vertx.executeBlocking(() -> service.putResource(descriptor, body, overwrite), false);
                })
                .onSuccess((metadata) -> {
                    if (metadata == null) {
                        context.respond(HttpStatus.CONFLICT, "Resource already exists: " + descriptor.getUrl());
                    } else {
                        context.respond(HttpStatus.OK, metadata);
                    }
                })
                .onFailure(error -> {
                    if (error instanceof HttpException exception) {
                        context.respond(exception.getStatus(), exception.getMessage());
                    } else if (error instanceof IllegalArgumentException badRequest) {
                        context.respond(HttpStatus.BAD_REQUEST, badRequest.getMessage());
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

        return vertx.executeBlocking(() -> {
                    String bucketName = descriptor.getBucketName();
                    String bucketLocation = descriptor.getBucketLocation();
                    return lockService.underBucketLock(bucketLocation, () -> {
                        invitationService.cleanUpResourceLink(bucketName, bucketLocation, descriptor);
                        shareService.revokeSharedResource(bucketName, bucketLocation, descriptor);
                        return service.deleteResource(descriptor);
                    });
                }, false)
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