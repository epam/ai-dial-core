package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.ResourceSignInRequest;
import com.epam.aidial.core.config.ResourceSignOutRequest;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;
import com.epam.aidial.core.credentials.service.ResourceCredentialsManager;

import java.util.Map;
import java.util.Set;

@Slf4j
public class ToolSetCredentialsController {

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final ResourceCredentialsManager resourceCredentialsManager;
    private final AccessService accessService;
    private final EncryptionService encryptionService;
    private final DeploymentService deploymentService;

    public ToolSetCredentialsController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.resourceCredentialsManager = proxy.getResourceCredentialsManager();
        this.accessService = proxy.getAccessService();
        this.encryptionService = proxy.getEncryptionService();
        this.deploymentService = proxy.getDeploymentService();
    }

    public Future<?> signIn() {
        context.getRequest()
                .body()
                .compose(body -> {
                    ResourceSignInRequest resourceSignInRequest = ProxyUtil.convertToObject(body, ResourceSignInRequest.class);
                    String toolsetId = resourceSignInRequest.getUrl();
                    return taskExecutor.submit(() -> {
                        Deployment deployment = deploymentService.findDeployment(context, toolsetId);
                        if (deployment instanceof ToolSet toolSet) {
                            verifyAccess(toolsetId, resourceSignInRequest.getCredentialsLevel());
                            ResourceCredentials resourceCredentials = resourceCredentialsManager.createResourceCredentials(
                                toolSet.getAuthSettings(),
                                resourceSignInRequest,
                                context.getUserSub());
                            return clearResourceCredentialsSecrets(resourceCredentials);
                        }
                        throw new ResourceNotFoundException("Toolset is not found: " + toolsetId);
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
                    ResourceSignOutRequest resourceSignOutRequest = ProxyUtil.convertToObject(body, ResourceSignOutRequest.class);
                    verifyAccess(resourceSignOutRequest.getUrl(), resourceSignOutRequest.getCredentialsLevel());

                    return resourceCredentialsManager.deleteResourceCredentials(resourceSignOutRequest, context.getUserSub());
                }))
                .onSuccess(removed -> context.respond(HttpStatus.OK, removed))
                .onFailure(error ->
                    respondError("Can't signOut from Toolset", error));

        return Future.succeededFuture();
    }

    private void verifyAccess(String toolSetId,
                              CredentialsLevel credentialsLevel) {

        ResourceDescriptor resourceDescriptor;
        try {
            String url = UrlUtil.encodePath(toolSetId);
            resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(url, encryptionService);
        } catch (Throwable ignore) {
            // toolset is from config
            return;
        }
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
    private ResourceCredentials clearResourceCredentialsSecrets(ResourceCredentials resourceCredentials) {
        return ResourceCredentials.builder()
            .resourceId(resourceCredentials.getResourceId())
            .authenticationType(resourceCredentials.getAuthenticationType())
            .credentialsLevel(resourceCredentials.getCredentialsLevel())
            .build();
    }

    private void respondError(String message, Throwable error) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String body = null;

        switch (error) {
            case HttpException e -> {
                status = e.getStatus();
                body = e.getMessage();
            }
            case ResourceNotFoundException resourceNotFoundException -> {
                status = HttpStatus.NOT_FOUND;
                body = resourceNotFoundException.getMessage();
            }
            case IllegalArgumentException illegalArgumentException -> {
                status = HttpStatus.BAD_REQUEST;
                body = illegalArgumentException.getMessage();
            }
            case PermissionDeniedException permissionDeniedException -> {
                status = HttpStatus.FORBIDDEN;
                body = permissionDeniedException.getMessage();
            }
            case null, default -> log.warn(message, error);
        }

        context.respond(status, body);
    }
}
