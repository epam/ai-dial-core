package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ToolSetSignInRequest;
import com.epam.aidial.core.config.ToolSetSignOutRequest;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.service.toolset.credentials.ToolSetCredentialsService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

@Slf4j
public class ToolSetCredentialsController {

    private final ProxyContext context;
    private final Vertx vertx;
    private final ToolSetCredentialsService toolsetCredentialsService;
    private final AccessService accessService;

    private final EncryptionService encryptionService;

    public ToolSetCredentialsController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.vertx = proxy.getVertx();
        this.toolsetCredentialsService = proxy.getToolsetCredentialsService();
        this.accessService = proxy.getAccessService();
        this.encryptionService = proxy.getEncryptionService();
    }

    public Future<?> signIn() {
        context.getRequest()
            .body()
            .compose(body -> {
                ToolSetSignInRequest toolSetSignInRequest = ProxyUtil.convertToObject(body, ToolSetSignInRequest.class);
                ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(
                    toolSetSignInRequest.getToolSetUrl(), encryptionService);

                Map<ResourceDescriptor, Set<ResourceAccessType>> permissions =
                    accessService.lookupPermissions(Set.of(resourceDescriptor), context);

                if (!permissions.get(resourceDescriptor).contains(ResourceAccessType.READ)) {
                    throw new PermissionDeniedException("no read access to ToolSet resource");
                }

                return vertx.executeBlocking(() -> {
                    ToolSetCredentials toolSetCredentials = toolsetCredentialsService.createToolsetCredentials(
                        resourceDescriptor, toolSetSignInRequest);
                    return clearToolsetCredentialsSecrets(toolSetCredentials);
                });
            })
            .onSuccess(toolsetCredentials -> context.respond(HttpStatus.OK, toolsetCredentials))
            .onFailure(error -> respondError("Can't signIn into Toolset", error));

        return Future.succeededFuture();
    }

    public Future<?> signOut() {
        context.getRequest()
            .body()
            .compose(body -> vertx.executeBlocking(() -> {
                ToolSetSignOutRequest toolSetSignOutRequest = ProxyUtil.convertToObject(body, ToolSetSignOutRequest.class);
                ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(
                    toolSetSignOutRequest.getToolSetUrl(), encryptionService);

                Map<ResourceDescriptor, Set<ResourceAccessType>> permissions =
                    accessService.lookupPermissions(Set.of(resourceDescriptor), context);

                if (!permissions.get(resourceDescriptor).contains(ResourceAccessType.READ)) {
                    throw new PermissionDeniedException("no read access to ToolSet resource");
                }

                return toolsetCredentialsService.deleteToolSetCredentials(resourceDescriptor, toolSetSignOutRequest);
            }))
            .onSuccess(removed -> context.respond(HttpStatus.OK, removed))
            .onFailure(error -> respondError("Can't signOut from Toolset", error));

        return Future.succeededFuture();
    }


    // TODO: Create dto for 'public' credentials information?
    private ToolSetCredentials clearToolsetCredentialsSecrets(ToolSetCredentials toolSetCredentials) {
        return ToolSetCredentials.builder()
            .toolSetName(toolSetCredentials.getToolSetName())
            .toolsetAuthenticationType(toolSetCredentials.getToolsetAuthenticationType())
            .credentialsLevel(toolSetCredentials.getCredentialsLevel())
            .status(toolSetCredentials.getStatus())
            .build();
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
}
