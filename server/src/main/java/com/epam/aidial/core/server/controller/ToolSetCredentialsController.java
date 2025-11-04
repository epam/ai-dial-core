package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignOutRequest;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.CredentialsDescriptorFactory;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.ValidationUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

@Slf4j
public class ToolSetCredentialsController {

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final ResourceCredentialsService resourceCredentialsService;
    private final AccessService accessService;
    private final EncryptionService encryptionService;
    private final DeploymentService deploymentService;
    private final ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;

    public ToolSetCredentialsController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.accessService = proxy.getAccessService();
        this.encryptionService = proxy.getEncryptionService();
        this.deploymentService = proxy.getDeploymentService();
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
        this.resourceAuthSettingsEncryptionService = proxy.getResourceAuthSettingsEncryptionService();
    }

    // TODO: fix case with toolset from config
    public Future<?> signIn() {
        context.getRequest()
                .body()
                .compose(body -> {
                    ResourceSignInRequest resourceSignInRequest = ProxyUtil.convertToObject(body, ResourceSignInRequest.class);
                    ValidationUtil.validate(resourceSignInRequest);
                    String toolsetId = resourceSignInRequest.getUrl();
                    return taskExecutor.submit(() -> {
                        Deployment deployment = deploymentService.findDeployment(context, toolsetId);
                        if (deployment instanceof ToolSet toolSet) {
                            String encodedResourceUrl = UrlUtil.encodePath(toolsetId);
                            verifyAccess(encodedResourceUrl, resourceSignInRequest.getCredentialsLevel());
                            CredentialsDescriptor credentialsDescriptor = CredentialsDescriptorFactory.fromAnyUrl(
                                    encodedResourceUrl, resourceSignInRequest.getCredentialsLevel(), context);
                            ResourceAuthSettings resourceAuthSettings = toolSet.getAuthSettings();
                            resourceAuthSettingsEncryptionService.decrypt(deployment.getName(),
                                    new BucketInfo(credentialsDescriptor.getBucketName(), credentialsDescriptor.getBucketLocation()),
                                    resourceAuthSettings);
                            resourceCredentialsService.addResourceCredentials(
                                    credentialsDescriptor,
                                    resourceAuthSettings,
                                    resourceSignInRequest,
                                    context.getUserSub());
                            return true;
                        }
                        throw new ResourceNotFoundException("Toolset is not found: " + toolsetId);
                    });
                })
                .onSuccess(added -> context.respond(HttpStatus.OK, added))
                .onFailure(error ->
                        respondError("Can't signIn into Toolset", error));

        return Future.succeededFuture();
    }

    // TODO: fix case with toolset from config
    public Future<?> signOut() {
        context.getRequest()
                .body()
                .compose(body -> taskExecutor.submit(() -> {
                    ResourceSignOutRequest resourceSignOutRequest = ProxyUtil.convertToObject(body, ResourceSignOutRequest.class);
                    String encodedResourceUrl = UrlUtil.encodePath(resourceSignOutRequest.getUrl());
                    ValidationUtil.validate(resourceSignOutRequest);
                    verifyAccess(encodedResourceUrl, resourceSignOutRequest.getCredentialsLevel());
                    CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(encodedResourceUrl, context);
                    return resourceCredentialsService.deleteResourceCredentials(
                            credentialsLocator,
                            resourceSignOutRequest,
                            context.getUserSub());
                }))
                .onSuccess(removed -> context.respond(HttpStatus.OK, removed))
                .onFailure(error ->
                        respondError("Can't signOut from Toolset", error));

        return Future.succeededFuture();
    }

    private void verifyAccess(String encodedResourceUrl, CredentialsLevel credentialsLevel) {
        ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(encodedResourceUrl, encryptionService);
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = accessService.lookupPermissions(Set.of(resourceDescriptor), context);

        if (credentialsLevel.equals(CredentialsLevel.GLOBAL)
                && !permissions.get(resourceDescriptor).containsAll(ResourceAccessType.ALL)) {
            throw new PermissionDeniedException("No read and write access to ToolSet resource");
        }

        if (!permissions.get(resourceDescriptor).contains(ResourceAccessType.READ)) {
            throw new PermissionDeniedException("No read access to ToolSet resource");
        }
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
            case ConstraintViolationException constraintViolationException -> {
                status = HttpStatus.BAD_REQUEST;
                body = constraintViolationException.getMessage();
            }
            case PermissionDeniedException permissionDeniedException -> {
                status = HttpStatus.FORBIDDEN;
                body = permissionDeniedException.getMessage();
            }
            case EncryptionException encryptionException -> body = encryptionException.getMessage();
            case null, default -> log.warn(message, error);
        }

        context.respond(status, body);
    }
}
