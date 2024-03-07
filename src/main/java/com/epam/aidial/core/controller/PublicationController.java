package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Publication;
import com.epam.aidial.core.data.Publications;
import com.epam.aidial.core.data.ResourceLink;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.service.PublicationService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class PublicationController {

    private final Vertx vertx;
    private final EncryptionService encryptService;
    private final PublicationService publicationService;
    private final ProxyContext context;

    public PublicationController(Proxy proxy, ProxyContext context) {
        this.vertx = proxy.getVertx();
        this.encryptService = proxy.getEncryptionService();
        this.publicationService = proxy.getPublicationService();
        this.context = context;
    }

    public Future<?> listPublications() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescription resource = decodePublication(url);
                    checkAccess(resource);
                    return vertx.executeBlocking(() -> publicationService.listPublications(resource));
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
                    ResourceDescription resource = decodePublication(url);
                    checkAccess(resource);
                    return vertx.executeBlocking(() -> publicationService.getPublication(resource));
                })
                .onSuccess(publication -> {
                    if (publication == null) {
                        context.respond(HttpStatus.NOT_FOUND);
                    } else {
                        context.respond(HttpStatus.OK, publication);
                    }
                })
                .onFailure(error -> respondError("Can't get publication", error));

        return Future.succeededFuture();
    }

    public Future<?> createPublication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    Publication publication = ProxyUtil.convertToObject(body, Publication.class);
                    ResourceDescription resource = decodePublication(publication.getUrl());
                    checkAccess(resource);
                    return vertx.executeBlocking(() -> publicationService.createPublication(resource, publication));
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
                    ResourceDescription resource = decodePublication(url);
                    checkAccess(resource);
                    return vertx.executeBlocking(() -> publicationService.deletePublication(resource));
                })
                .onSuccess(deleted -> context.respond(deleted ? HttpStatus.OK : HttpStatus.NOT_FOUND))
                .onFailure(error -> respondError("Can't delete publication", error));

        return Future.succeededFuture();
    }

    private void respondError(String message, Throwable error) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String body = null;

        if (error instanceof HttpException e) {
            status = e.getStatus();
            body = e.getMessage();
        } else if (error instanceof IllegalArgumentException e) {
            status = HttpStatus.BAD_REQUEST;
            body = e.getMessage();
        } else {
            log.warn(message, error);
        }

        context.respond(status, body);
    }

    private ResourceDescription decodePublication(String path) {
        ResourceDescription resource;
        try {
            resource = ResourceDescription.fromLink(path, encryptService);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid resource: " + path, e);
        }

        if (resource.getType() != ResourceType.PUBLICATION) {
            throw new IllegalArgumentException("Invalid resource: " + path);
        }

        return resource;
    }

    private void checkAccess(ResourceDescription resource) {
        String bucket = BlobStorageUtil.buildInitiatorBucket(context);

        if (!resource.getBucketLocation().equals(bucket)) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden resource: " + resource.getUrl());
        }
    }
}