package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.ListPublishedResourcesRequest;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.Publication;
import com.epam.aidial.core.data.Publications;
import com.epam.aidial.core.data.RejectPublicationRequest;
import com.epam.aidial.core.data.ResourceLink;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.Rules;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.PermissionDeniedException;
import com.epam.aidial.core.service.PublicationService;
import com.epam.aidial.core.service.ResourceNotFoundException;
import com.epam.aidial.core.service.RuleService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;

@Slf4j
@RequiredArgsConstructor
public class PublicationController {

    private final Vertx vertx;
    private final LockService lockService;
    private final AccessService accessService;
    private final EncryptionService encryptService;
    private final PublicationService publicationService;
    private final RuleService ruleService;
    private final ProxyContext context;

    public PublicationController(Proxy proxy, ProxyContext context) {
        this.vertx = proxy.getVertx();
        this.lockService = proxy.getLockService();
        this.accessService = proxy.getAccessService();
        this.encryptService = proxy.getEncryptionService();
        this.publicationService = proxy.getPublicationService();
        this.ruleService = proxy.getRuleService();
        this.context = context;
    }

    public Future<?> listPublications() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescription resource = decodePublication(url, true);
                    checkAccess(resource, resource.isPrivate());
                    return vertx.executeBlocking(() -> publicationService.listPublications(resource), false);
                })
                .onSuccess(publications -> context.respond(HttpStatus.OK, new Publications(publications)))
                .onFailure(error -> respondError("Can't list publications", error));

        return Future.succeededFuture();
    }

    public Future<?> getPublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescription resource = decodePublication(url, false);
                    checkAccess(resource, true);
                    return vertx.executeBlocking(() -> publicationService.getPublication(resource), false);
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK, publication))
                .onFailure(error -> respondError("Can't get publication", error));

        return Future.succeededFuture();
    }

    public Future<?> createPublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    Publication publication = ProxyUtil.convertToObject(body, Publication.class);
                    return vertx.executeBlocking(() -> publicationService.createPublication(context, publication), false);
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK, publication))
                .onFailure(error -> respondError("Can't create publication", error));

        return Future.succeededFuture();
    }

    public Future<?> deletePublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescription resource = decodePublication(url, false);
                    checkAccess(resource, true);
                    return vertx.executeBlocking(() -> publicationService.deletePublication(resource), false);
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK))
                .onFailure(error -> respondError("Can't delete publication", error));

        return Future.succeededFuture();
    }

    public Future<?> approvePublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescription resource = decodePublication(url, false);
                    checkAccess(resource, false);
                    return vertx.executeBlocking(() ->
                            lockService.underBucketLock(BlobStorageUtil.PUBLIC_LOCATION,
                                    () -> publicationService.approvePublication(resource)), false);
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK, publication))
                .onFailure(error -> respondError("Can't approve publication", error));

        return Future.succeededFuture();
    }

    public Future<?> rejectPublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    RejectPublicationRequest request = ProxyUtil.convertToObject(body, RejectPublicationRequest.class);
                    String url = request.url();
                    ResourceDescription resource = decodePublication(url, false);
                    checkAccess(resource, false);
                    return vertx.executeBlocking(() -> publicationService.rejectPublication(resource, request), false);
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK, publication))
                .onFailure(error -> respondError("Can't reject publication", error));

        return Future.succeededFuture();
    }

    public Future<?> listRules() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescription rule = decodeRule(url);
                    checkRuleAccess(rule);
                    return vertx.executeBlocking(() -> ruleService.listRules(rule), false);
                })
                .onSuccess(rules -> context.respond(HttpStatus.OK, new Rules(rules)))
                .onFailure(error -> respondError("Can't list rules", error));

        return Future.succeededFuture();
    }

    public Future<?> listPublishedResources() {
        context.getRequest()
                .body()
                .compose(body -> {
                    ListPublishedResourcesRequest request = ProxyUtil.convertToObject(body, ListPublishedResourcesRequest.class);
                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptService.encrypt(bucketLocation);
                    return vertx.executeBlocking(() -> {
                        Collection<MetadataBase> metadata =
                                publicationService.listPublishedResources(request, bucket, bucketLocation);
                        if (context.getBooleanRequestQueryParam("permissions")) {
                            accessService.populatePermissions(context, bucketLocation, metadata);
                        }
                        return metadata;
                    }, false);
                })
                .onSuccess(metadata -> context.respond(HttpStatus.OK, metadata))
                .onFailure(error -> respondError("Can't list published resources", error));

        return Future.succeededFuture();
    }

    private void respondError(String message, Throwable error) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String body = null;

        if (error instanceof HttpException e) {
            status = e.getStatus();
            body = e.getMessage();
        } else if (error instanceof ResourceNotFoundException) {
            status = HttpStatus.NOT_FOUND;
            body = error.getMessage();
        } else if (error instanceof IllegalArgumentException e) {
            status = HttpStatus.BAD_REQUEST;
            body = e.getMessage();
        } else if (error instanceof PermissionDeniedException e) {
            status = HttpStatus.FORBIDDEN;
            body = e.getMessage();
        } else {
            log.warn(message, error);
        }

        context.respond(status, body);
    }

    private ResourceDescription decodePublication(String path, boolean allowPublic) {
        ResourceDescription resource;
        try {
            resource = ResourceDescription.fromAnyUrl(path, encryptService);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid resource: " + path, e);
        }

        if (resource.getType() != ResourceType.PUBLICATION) {
            throw new IllegalArgumentException("Invalid resource: " + path);
        }

        if (!allowPublic && resource.isPublic()) {
            throw new IllegalArgumentException("Invalid resource: " + path);
        }

        return resource;
    }

    private ResourceDescription decodeRule(String path) {
        try {
            if (!path.startsWith(BlobStorageUtil.PUBLIC_LOCATION)) {
                throw new IllegalArgumentException();
            }

            String folder = path.substring(BlobStorageUtil.PUBLIC_LOCATION.length());
            ResourceDescription resource = ResourceDescription.fromEncoded(ResourceType.RULES, BlobStorageUtil.PUBLIC_BUCKET, BlobStorageUtil.PUBLIC_LOCATION, folder);

            if (!resource.isFolder()) {
                throw new IllegalArgumentException();
            }

            return resource;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid resource: " + path, e);
        }
    }

    private void checkAccess(ResourceDescription resource, boolean allowUser) {
        boolean hasAccess = isAdmin();

        if (!hasAccess && allowUser) {
            String bucket = BlobStorageUtil.buildInitiatorBucket(context);
            hasAccess = resource.getBucketLocation().equals(bucket);
        }

        if (!hasAccess) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden resource: " + resource.getUrl());
        }
    }

    private boolean isAdmin() {
        return accessService.hasAdminAccess(context);
    }

    private void checkRuleAccess(ResourceDescription rule) {
        if (!accessService.hasReadAccess(rule, context)) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden resource: " + rule.getUrl());
        }
    }
}