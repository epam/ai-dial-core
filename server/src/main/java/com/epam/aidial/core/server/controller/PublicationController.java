package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ListPublishedResourcesRequest;
import com.epam.aidial.core.server.data.Publication;
import com.epam.aidial.core.server.data.Publications;
import com.epam.aidial.core.server.data.RejectPublicationRequest;
import com.epam.aidial.core.server.data.ResourceLink;
import com.epam.aidial.core.server.data.Rules;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.PublicationService;
import com.epam.aidial.core.server.service.RuleService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class PublicationController {

    private final AsyncTaskExecutor taskExecutor;
    private final LockService lockService;
    private final AccessService accessService;
    private final EncryptionService encryptService;
    private final PublicationService publicationService;
    private final RuleService ruleService;
    private final ProxyContext context;

    public PublicationController(Proxy proxy, ProxyContext context) {
        this.taskExecutor = proxy.getTaskExecutor();
        this.lockService = proxy.getLockService();
        this.accessService = proxy.getAccessService();
        this.encryptService = proxy.getEncryptionService();
        this.publicationService = proxy.getPublicationService();
        this.ruleService = proxy.getRuleService();
        this.context = context;
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/publication/list",
            operationId = "getPublications",
            tags = {"Publications"},
            requestBody = ResourceLink.class,
            responses = {
                @ApiResponse(code = 200, description = "Success", body = Publication.class, wrapper = List.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> listPublications() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodePublication(url, true);
                    checkAccess(resource, resource.isPrivate());
                    return taskExecutor.submit(() -> publicationService.listPublications(resource));
                })
                .onSuccess(publications -> context.respond(HttpStatus.OK, new Publications(publications)))
                .onFailure(error -> respondError("Can't list publications", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/publication/get",
            operationId = "getPublication",
            requestBody = ResourceLink.class,
            tags = {"Publications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Publication.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> getPublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodePublication(url, false);
                    checkAccess(resource, true);
                    return taskExecutor.submit(() -> publicationService.getPublication(resource));
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK, publication))
                .onFailure(error -> respondError("Can't get publication", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/publication/create",
            operationId = "createPublication",
            requestBody = Publication.class,
            tags = {"Publications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Publication.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> createPublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    Publication publication = ProxyUtil.convertToObject(body, Publication.class);
                    return taskExecutor.submit(() -> publicationService.createPublication(context, publication));
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK, publication))
                .onFailure(error -> respondError("Can't create publication", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/publication/delete",
            operationId = "deletePublication",
            requestBody = ResourceLink.class,
            tags = {"Publications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> deletePublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodePublication(url, false);
                    checkAccess(resource, true);
                    return taskExecutor.submit(() -> publicationService.deletePublication(context, resource));
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK))
                .onFailure(error -> respondError("Can't delete publication", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/publication/update",
            operationId = "updatePublication",
            requestBody = Publication.class,
            tags = {"Publications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Publication.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> updatePublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    Publication publication = ProxyUtil.convertToObject(body, Publication.class);
                    if (!isAdmin()) {
                        throw new HttpException(HttpStatus.FORBIDDEN, "Only admin can update publications");
                    }
                    return taskExecutor.submit(() -> publicationService.updatePublication(context, publication));
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK, publication))
                .onFailure(error -> respondError("Can't update publication", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/publication/approve",
            operationId = "approvePublication",
            requestBody = ResourceLink.class,
            tags = {"Publications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Publication.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> approvePublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodePublication(url, false);
                    checkAccess(resource, false);
                    return taskExecutor.submit(() ->
                            lockService.underBucketLock(ResourceDescriptor.PUBLIC_LOCATION,
                                    () -> publicationService.approvePublication(context, resource)));
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK, publication))
                .onFailure(error -> respondError("Can't approve publication", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/publication/reject",
            operationId = "rejectPublication",
            requestBody = RejectPublicationRequest.class,
            tags = {"Publications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Publication.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> rejectPublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    RejectPublicationRequest request = ProxyUtil.convertToObject(body, RejectPublicationRequest.class);
                    String url = request.url();
                    ResourceDescriptor resource = decodePublication(url, false);
                    checkAccess(resource, false);
                    return taskExecutor.submit(() -> publicationService.rejectPublication(context, resource, request));
                })
                .onSuccess(publication -> context.respond(HttpStatus.OK, publication))
                .onFailure(error -> respondError("Can't reject publication", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/publication/rule/list",
            operationId = "getPublicationRules",
            requestBody = ResourceLink.class,
            tags = {"Publications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Rules.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> listRules() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor rule = decodeRule(url);
                    checkRuleAccess(rule);
                    return taskExecutor.submit(() -> ruleService.listRules(rule));
                })
                .onSuccess(rules -> context.respond(HttpStatus.OK, new Rules(rules)))
                .onFailure(error -> respondError("Can't list rules", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/publication/resource/list",
            operationId = "listPublishedResources",
            requestBody = ListPublishedResourcesRequest.class,
            tags = {"Publications"},
            parameters = {
                    @ApiParameter(name = "permissions", in = ParameterIn.QUERY)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = MetadataBase.class, wrapper = Collection.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> listPublishedResources() {
        context.getRequest()
                .body()
                .compose(body -> {
                    ListPublishedResourcesRequest request = ProxyUtil.convertToObject(body, ListPublishedResourcesRequest.class);
                    String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
                    String bucket = encryptService.encrypt(bucketLocation);
                    return taskExecutor.submit(() -> {
                        Collection<MetadataBase> metadata =
                                publicationService.listPublishedResources(request, bucket, bucketLocation);
                        if (context.getBooleanRequestQueryParam("permissions")) {
                            accessService.populatePermissions(context, metadata);
                        }
                        return metadata;
                    });
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
        }

        context.respond(status, body);
        
        if (!(error instanceof HttpException)
                && !(error instanceof ResourceNotFoundException)
                && !(error instanceof IllegalArgumentException)
                && !(error instanceof PermissionDeniedException)) {
            log.warn(message, error);
        }
    }

    private ResourceDescriptor decodePublication(String path, boolean allowPublic) {
        ResourceDescriptor resource;
        try {
            resource = ResourceDescriptorFactory.fromAnyUrl(path, encryptService);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid resource: " + path, e);
        }

        if (resource.getType() != ResourceTypes.PUBLICATION) {
            throw new IllegalArgumentException("Invalid resource: " + path);
        }

        if (!allowPublic && resource.isPublic()) {
            throw new IllegalArgumentException("Invalid resource: " + path);
        }

        return resource;
    }

    private ResourceDescriptor decodeRule(String path) {
        try {
            if (!path.startsWith(ResourceDescriptor.PUBLIC_LOCATION)) {
                throw new IllegalArgumentException();
            }

            String folder = path.substring(ResourceDescriptor.PUBLIC_LOCATION.length());
            ResourceDescriptor resource = ResourceDescriptorFactory.fromEncoded(ResourceTypes.RULES, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, folder);

            if (!resource.isFolder()) {
                throw new IllegalArgumentException();
            }

            return resource;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid resource: " + path, e);
        }
    }

    private void checkAccess(ResourceDescriptor resource, boolean allowUser) {
        boolean hasAccess = isAdmin();

        if (!hasAccess && allowUser) {
            String bucket = BucketBuilder.buildInitiatorBucket(context);
            hasAccess = resource.getBucketLocation().equals(bucket);
        }

        if (!hasAccess) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden resource: " + resource.getUrl());
        }
    }

    private boolean isAdmin() {
        return accessService.hasAdminAccess(context);
    }

    private void checkRuleAccess(ResourceDescriptor rule) {
        if (!accessService.hasReadAccess(rule, context)) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden resource: " + rule.getUrl());
        }
    }
}