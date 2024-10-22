package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Conversation;
import com.epam.aidial.core.server.data.MetadataBase;
import com.epam.aidial.core.server.data.Prompt;
import com.epam.aidial.core.server.data.ResourceItemMetadata;
import com.epam.aidial.core.server.data.ResourceType;
import com.epam.aidial.core.server.resource.ResourceDescriptor;
import com.epam.aidial.core.server.resource.ResourceDescriptorFactory;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.InvitationService;
import com.epam.aidial.core.server.service.LockService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.service.ResourceService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.util.EtagHeader;
import com.epam.aidial.core.server.util.HttpException;
import com.epam.aidial.core.server.util.HttpStatus;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@SuppressWarnings("checkstyle:Indentation")
public class ResourceController extends AccessControlBaseController {

    private final Vertx vertx;
    private final ResourceService service;
    private final ShareService shareService;
    private final LockService lockService;
    private final ApplicationService applicationService;
    private final InvitationService invitationService;
    private final boolean metadata;
    private final AccessService accessService;

    public ResourceController(Proxy proxy, ProxyContext context, boolean metadata) {
        // PUT and DELETE require write access, GET - read
        super(proxy, context, !HttpMethod.GET.equals(context.getRequest().method()));
        this.vertx = proxy.getVertx();
        this.service = proxy.getResourceService();
        this.applicationService = proxy.getApplicationService();
        this.shareService = proxy.getShareService();
        this.accessService = proxy.getAccessService();
        this.lockService = proxy.getLockService();
        this.invitationService = proxy.getInvitationService();
        this.metadata = metadata;
    }

    @Override
    protected Future<?> handle(ResourceDescriptor descriptor, boolean hasWriteAccess) {
        if (context.getRequest().method() == HttpMethod.GET) {
            return metadata ? getMetadata(descriptor) : getResource(descriptor, hasWriteAccess);
        }

        if (context.getRequest().method() == HttpMethod.PUT) {
            return putResource(descriptor);
        }

        if (context.getRequest().method() == HttpMethod.DELETE) {
            return deleteResource(descriptor);
        }
        log.warn("Unsupported HTTP method for accessing resource {}", descriptor.getUrl());
        return context.respond(HttpStatus.BAD_REQUEST, "Unsupported HTTP method");
    }

    private String getContentType() {
        String acceptType = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return acceptType != null && metadata && acceptType.contains(MetadataBase.MIME_TYPE)
                ? MetadataBase.MIME_TYPE
                : "application/json";
    }

    private Future<?> getMetadata(ResourceDescriptor descriptor) {
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

        vertx.executeBlocking(() -> service.getMetadata(descriptor, token, limit, recursive), false)
                .onSuccess(result -> {
                    if (result == null) {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
                    } else {
                        accessService.filterForbidden(context, descriptor, result);
                        if (context.getBooleanRequestQueryParam("permissions")) {
                            accessService.populatePermissions(context, List.of(result));
                        }
                        context.respond(HttpStatus.OK, getContentType(), result);
                    }
                })
                .onFailure(error -> {
                    log.warn("Can't list resource: {}", descriptor.getUrl(), error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                });

        return Future.succeededFuture();
    }

    private Future<?> getResource(ResourceDescriptor descriptor, boolean hasWriteAccess) {
        if (descriptor.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        Future<Pair<ResourceItemMetadata, String>> responseFuture = (descriptor.getType() == ResourceType.APPLICATION)
                ? getApplicationData(descriptor, hasWriteAccess) : getResourceData(descriptor);

        responseFuture.onSuccess(pair -> {
                    context.putHeader(HttpHeaders.ETAG, pair.getKey().getEtag())
                            .exposeHeaders()
                            .respond(HttpStatus.OK, pair.getValue());
                })
                .onFailure(error -> handleError(descriptor, error));

        return Future.succeededFuture();
    }

    private Future<Pair<ResourceItemMetadata, String>> getApplicationData(ResourceDescriptor descriptor, boolean hasWriteAccess) {
        return vertx.executeBlocking(() -> {
            Pair<ResourceItemMetadata, Application> result = applicationService.getApplication(descriptor);
            ResourceItemMetadata meta = result.getKey();

            Application application = result.getValue();
            String body = hasWriteAccess
                    ? ProxyUtil.convertToString(application)
                    : ProxyUtil.convertToString(ApplicationUtil.mapApplication(application));

            return Pair.of(meta, body);

        }, false);
    }

    private Future<Pair<ResourceItemMetadata, String>> getResourceData(ResourceDescriptor descriptor) {
        return vertx.executeBlocking(() -> {
            Pair<ResourceItemMetadata, String> result = service.getResourceWithMetadata(descriptor);

            if (result == null) {
                throw new ResourceNotFoundException();
            }

            return result;
        }, false);
    }

    private Future<?> putResource(ResourceDescriptor descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        if (!ResourceDescriptorFactory.isValidResourcePath(descriptor)) {
            return context.respond(HttpStatus.BAD_REQUEST, "Resource name and/or parent folders must not end with .(dot)");
        }

        int contentLength = ProxyUtil.contentLength(context.getRequest(), 0);
        int contentLimit = service.getMaxSize();

        if (contentLength > contentLimit) {
            String message = "Resource size: %s exceeds max limit: %s".formatted(contentLength, contentLimit);
            return context.respond(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
        }

        Future<Pair<EtagHeader, String>> requestFuture = context.getRequest().body().map(bytes -> {
            if (bytes.length() > contentLimit) {
                String message = "Resource size: %s exceeds max limit: %s".formatted(bytes.length(), contentLimit);
                throw new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
            }

            EtagHeader etag = EtagHeader.fromRequest(context.getRequest());
            String body = bytes.toString(StandardCharsets.UTF_8);

            return Pair.of(etag, body);
        });

        Future<ResourceItemMetadata> responseFuture;

        if (descriptor.getType() == ResourceType.APPLICATION) {
            responseFuture =  requestFuture.compose(pair -> {
                EtagHeader etag = pair.getKey();
                Application application = ProxyUtil.convertToObject(pair.getValue(), Application.class);
                return vertx.executeBlocking(() -> applicationService.putApplication(descriptor, etag, application).getKey(), false);
            });
        } else {
           responseFuture =  requestFuture.compose(pair -> {
                EtagHeader etag = pair.getKey();
                String body = pair.getValue();
                validateRequestBody(descriptor, body);
                return vertx.executeBlocking(() -> service.putResource(descriptor, body, etag), false);
            });
        }

        responseFuture.onSuccess((metadata) -> {
                    context.putHeader(HttpHeaders.ETAG, metadata.getEtag())
                            .exposeHeaders()
                            .respond(HttpStatus.OK, metadata);
                })
                .onFailure(error -> handleError(descriptor, error));

        return Future.succeededFuture();
    }

    private Future<?> deleteResource(ResourceDescriptor descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        vertx.executeBlocking(() -> {
                    EtagHeader etag = EtagHeader.fromRequest(context.getRequest());
                    String bucketName = descriptor.getBucketName();
                    String bucketLocation = descriptor.getBucketLocation();

                    return lockService.underBucketLock(bucketLocation, () -> {
                        invitationService.cleanUpResourceLink(bucketName, bucketLocation, descriptor);
                        shareService.revokeSharedResource(bucketName, bucketLocation, descriptor);

                        boolean deleted = true;

                        if (descriptor.getType() == ResourceType.APPLICATION) {
                            applicationService.deleteApplication(descriptor, etag);
                        } else {
                           deleted = service.deleteResource(descriptor, etag);
                        }

                        if (!deleted) {
                            throw new ResourceNotFoundException();
                        }

                        return null;
                    });
                }, false)
                .onSuccess(ignore -> context.respond(HttpStatus.OK))
                .onFailure(error -> handleError(descriptor, error));

        return Future.succeededFuture();
    }

    private void handleError(ResourceDescriptor descriptor, Throwable error) {
        if (error instanceof HttpException exception) {
            context.respond(exception.getStatus(), exception.getMessage());
        } else if (error instanceof IllegalArgumentException) {
            context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
        } else if (error instanceof PermissionDeniedException) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden: " + descriptor.getUrl());
        } else {
            log.warn("Can't handle resource request: {}", descriptor.getUrl(), error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static void validateRequestBody(ResourceDescriptor descriptor, String body) {
        switch (descriptor.getType()) {
            case PROMPT -> ProxyUtil.convertToObject(body, Prompt.class);
            case CONVERSATION -> ProxyUtil.convertToObject(body, Conversation.class);
            default -> throw new IllegalArgumentException("Unsupported resource type " + descriptor.getType());
        }
    }
}