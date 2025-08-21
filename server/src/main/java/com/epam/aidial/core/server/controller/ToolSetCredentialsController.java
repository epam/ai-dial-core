package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.CredentialsLevel;
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
import com.epam.aidial.core.server.service.credentials.ToolSetCredentialsManager;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

@Slf4j
public class ToolSetCredentialsController {

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final ToolSetCredentialsManager toolSetCredentialsManager;
    private final AccessService accessService;
    private final EncryptionService encryptionService;

    public ToolSetCredentialsController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.toolSetCredentialsManager = proxy.getToolSetCredentialsManager();
        this.accessService = proxy.getAccessService();
        this.encryptionService = proxy.getEncryptionService();
    }

    public Future<?> signIn() {
        context.getRequest()
                .body()
                .compose(body -> {
                    ToolSetSignInRequest toolSetSignInRequest = ProxyUtil.convertToObject(body, ToolSetSignInRequest.class);
                    ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(toolSetSignInRequest.getUrl(), encryptionService);

                    verifyAccess(resourceDescriptor, toolSetSignInRequest.getCredentialsLevel());

                    return taskExecutor.submit(() -> {
                        ToolSetCredentials toolSetCredentials = toolSetCredentialsManager.createToolsetCredentials(resourceDescriptor, toolSetSignInRequest, context);
                        return clearToolsetCredentialsSecrets(toolSetCredentials);
                    });
                })
                .onSuccess(toolsetCredentials -> context.respond(HttpStatus.OK, toolsetCredentials))
                .onFailure(error ->
                    respondError("Can't signIn into Toolset", error));

        return Future.succeededFuture();
    }

    public Future<?> signOut() {
        context.getRequest()
                .body()
                .compose(body -> taskExecutor.submit(() -> {
                    ToolSetSignOutRequest toolSetSignOutRequest = ProxyUtil.convertToObject(body, ToolSetSignOutRequest.class);
                    ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(toolSetSignOutRequest.getUrl(), encryptionService);

                    verifyAccess(resourceDescriptor, toolSetSignOutRequest.getCredentialsLevel());

                    return toolSetCredentialsManager.deleteToolSetCredentials(toolSetSignOutRequest, context);
                }))
                .onSuccess(removed -> context.respond(HttpStatus.OK, removed))
                .onFailure(error ->
                    respondError("Can't signOut from Toolset", error));

        return Future.succeededFuture();
    }

    private void verifyAccess(ResourceDescriptor resourceDescriptor,
                              CredentialsLevel credentialsLevel) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = accessService.lookupPermissions(Set.of(resourceDescriptor), context);

        if (credentialsLevel.equals(CredentialsLevel.GLOBAL)
                && !permissions.get(resourceDescriptor).containsAll(ResourceAccessType.ALL)) {
            throw new PermissionDeniedException("no read and write access to ToolSet resource");
        }

        if (!permissions.get(resourceDescriptor).contains(ResourceAccessType.READ)) {
            throw new PermissionDeniedException("no read access to ToolSet resource");
        }
    }

    // TODO: Create dto for 'public' credentials information?
    private ToolSetCredentials clearToolsetCredentialsSecrets(ToolSetCredentials toolSetCredentials) {
        return ToolSetCredentials.builder()
            .toolSetName(toolSetCredentials.getToolSetName())
            .authenticationType(toolSetCredentials.getAuthenticationType())
            .credentialsLevel(toolSetCredentials.getCredentialsLevel())
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
