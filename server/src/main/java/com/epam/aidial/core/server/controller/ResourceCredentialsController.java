package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.SecuredResource;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignOutRequest;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.ValidationUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
public class ResourceCredentialsController {

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final ResourceCredentialsService resourceCredentialsService;
    private final AccessService accessService;
    private final EncryptionService encryptionService;
    private final DeploymentService deploymentService;
    private final ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;

    public ResourceCredentialsController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.accessService = proxy.getAccessService();
        this.encryptionService = proxy.getEncryptionService();
        this.deploymentService = proxy.getDeploymentService();
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
        this.resourceAuthSettingsEncryptionService = proxy.getResourceAuthSettingsEncryptionService();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/toolset/signin",
            operationId = "toolsetSignin",
            tags = {"Toolsets"},
            requestBody = @ApiSchema(implementation = ResourceSignInRequest.class),
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = Boolean.class))
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION)
    public Future<?> signIn() {
        context.getRequest()
                .body()
                .compose(body -> {
                    ResourceSignInRequest resourceSignInRequest = ProxyUtil.convertToObject(body, ResourceSignInRequest.class);
                    ValidationUtil.validate(resourceSignInRequest);
                    String resourceId = resourceSignInRequest.getUrl();
                    return taskExecutor.submit(() -> {
                        Deployment deployment = deploymentService.findDeployment(context, resourceId);
                        if (deployment instanceof SecuredResource securedResource) {
                            String encodedResourceUrl = UrlUtil.encodePath(resourceId);
                            ResourceAuthSettings resourceAuthSettings = securedResource.getAuthSettings();
                            CredentialsLevel credentialsLevel = resourceSignInRequest.getCredentialsLevel();
                            validateAuthType(resourceAuthSettings.getAuthenticationType(), resourceSignInRequest.getAuthenticationType());
                            if (context.getConfig().isDeploymentExists(deployment.getName())) {
                                verifyAccess(securedResource, credentialsLevel);
                            } else {
                                verifyAccess(encodedResourceUrl, credentialsLevel);
                                decryptAuthSettings(encodedResourceUrl, resourceAuthSettings);
                            }
                            
                            CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(encodedResourceUrl, context, ResourceTypes.TOOL_SET);
                            CredentialsDescriptor credentialsDescriptor = credentialsLocator.getCredentialsDescriptors().get(resourceSignInRequest.getCredentialsLevel());
                            resourceCredentialsService.addResourceCredentials(credentialsDescriptor, resourceAuthSettings,
                                    resourceSignInRequest, context.getInitiatorId());
                            return true;
                        }
                        throw new ResourceNotFoundException("Resource is not found: " + resourceId);
                    });
                })
                .onSuccess(added -> context.respond(HttpStatus.OK, added))
                .onFailure(error ->
                        respondError("Can't signIn into Resource", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/toolset/signout",
            operationId = "toolSetSignout",
            tags = {"Toolsets"},
            requestBody = @ApiSchema(implementation = ResourceSignOutRequest.class),
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = Boolean.class))
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION)
    public Future<?> signOut() {
        context.getRequest()
                .body()
                .compose(body -> taskExecutor.submit(() -> {
                    ResourceSignOutRequest resourceSignOutRequest = ProxyUtil.convertToObject(body, ResourceSignOutRequest.class);
                    String resourceId = resourceSignOutRequest.getUrl();
                    String encodedResourceId = UrlUtil.encodePath(resourceId);
                    CredentialsLevel credentialsLevel = resourceSignOutRequest.getCredentialsLevel();
                    ValidationUtil.validate(resourceSignOutRequest);

                    Deployment deployment = deploymentService.findDeployment(context, resourceId);
                    if (deployment instanceof SecuredResource securedResource) {
                        ResourceAuthSettings resourceAuthSettings = securedResource.getAuthSettings();
                        validateAuthType(resourceAuthSettings.getAuthenticationType(), resourceSignOutRequest.getAuthenticationType());
                        if (context.getConfig().isDeploymentExists(encodedResourceId)) {
                            verifyAccess(securedResource, credentialsLevel);
                        } else {
                            verifyAccess(encodedResourceId, credentialsLevel);
                        }
                    } else {
                        throw new ResourceNotFoundException("Resource is not found: " + encodedResourceId);
                    }

                    CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(encodedResourceId, context, ResourceTypes.TOOL_SET);
                    return resourceCredentialsService.deleteResourceCredentials(
                            credentialsLocator,
                            resourceSignOutRequest,
                            context.getInitiatorId());
                }))
                .onSuccess(removed -> context.respond(HttpStatus.OK, removed))
                .onFailure(error ->
                        respondError("Can't signOut from Resource", error));

        return Future.succeededFuture();
    }

    private void verifyAccess(SecuredResource securedResource,
                              CredentialsLevel credentialsLevel) {
        if (!securedResource.hasAccess(context.getUserRoles())) {
            throw new PermissionDeniedException("No read access to Resource");
        }

        if (credentialsLevel.equals(CredentialsLevel.GLOBAL)
                && !accessService.hasAdminAccess(context)) {
            throw new PermissionDeniedException("No read and write access to Resource");
        }
    }

    private void verifyAccess(String encodedResourceUrl,
                              CredentialsLevel credentialsLevel) {
        ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(encodedResourceUrl, encryptionService);
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = accessService.lookupPermissions(Set.of(resourceDescriptor), context);

        if (credentialsLevel.equals(CredentialsLevel.GLOBAL)
                && !permissions.get(resourceDescriptor).containsAll(ResourceAccessType.ALL)) {
            throw new PermissionDeniedException("No read and write access to Resource");
        }

        if (!permissions.get(resourceDescriptor).contains(ResourceAccessType.READ)) {
            throw new PermissionDeniedException("No read access to Resource");
        }
    }

    private void decryptAuthSettings(String encodedResourceUrl, ResourceAuthSettings resourceAuthSettings) {
        ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(encodedResourceUrl, encryptionService);
        BucketInfo resourceBucketInfo = new BucketInfo(resourceDescriptor.getBucketName(), resourceDescriptor.getBucketLocation());
        resourceAuthSettingsEncryptionService.decrypt(resourceDescriptor.getUrl(), resourceBucketInfo, resourceAuthSettings);
    }

    private void validateAuthType(AuthenticationType resourceAuthenticationType,
                                  AuthenticationType requestAuthenticationType) {
        if (!Objects.equals(resourceAuthenticationType, requestAuthenticationType)) {
            throw new IllegalArgumentException("Wrong authentication_type. Expected type: %s, provided: %s"
                    .formatted(resourceAuthenticationType, requestAuthenticationType));
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