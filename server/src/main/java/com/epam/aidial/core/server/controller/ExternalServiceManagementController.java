package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ExternalServiceData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admin/app-owner CRUD for an application's external-service definitions; static-config apps are read-only. */
@Slf4j
public class ExternalServiceManagementController {

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final ApplicationService applicationService;
    private final ExternalServiceService externalServiceService;
    private final AccessService accessService;
    private final EncryptionService encryptionService;
    private final ResourceCredentialsService resourceCredentialsService;
    private final ResourceAuthSettingsService resourceAuthSettingsService;

    public ExternalServiceManagementController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.applicationService = proxy.getApplicationService();
        this.externalServiceService = proxy.getExternalServiceService();
        this.accessService = proxy.getAccessService();
        this.encryptionService = proxy.getEncryptionService();
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
        this.resourceAuthSettingsService = proxy.getResourceAuthSettingsService();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/applications/{appId}/external-services",
            operationId = "listExternalServices",
            tags = {"External Services"},
            parameters = {
                    @ApiParameter(name = "appId", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_APP_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = ExternalServiceData.class))
            },
            responseProfile = ResponseProfile.AUTHORIZED_READ
    )
    public Future<?> listExternalServices(String appId) {
        taskExecutor.submit(() -> {
            ResolvedApp resolved = resolveAndAuthorize(appId);
            List<ExternalServiceData> result = new ArrayList<>();
            Map<String, ExternalService> services = resolved.application.getExternalServices();
            if (services != null) {
                services.forEach((id, service) -> result.add(toData(appId, id, service, true)));
            }
            return result;
        }).onSuccess(result -> context.respond(HttpStatus.OK, result))
                .onFailure(error -> respondError("Can't list external services", error));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/applications/{appId}/external-services/{id}",
            operationId = "getExternalService",
            tags = {"External Services"},
            parameters = {
                    @ApiParameter(name = "appId", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_APP_ID),
                    @ApiParameter(name = "id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = ExternalServiceData.class))
            },
            responseProfile = ResponseProfile.AUTHORIZED_READ
    )
    public Future<?> getExternalService(String appId, String serviceId) {
        taskExecutor.submit(() -> {
            ResolvedApp resolved = resolveAndAuthorize(appId);
            ExternalService service = getService(resolved.application, serviceId);
            return toData(appId, serviceId, service, true);
        }).onSuccess(data -> context.respond(HttpStatus.OK, data))
                .onFailure(error -> respondError("Can't get external service", error));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "PUT",
            path = "/v1/applications/{appId}/external-services/{id}",
            operationId = "putExternalService",
            requestBody = @ApiSchema(implementation = ExternalService.class),
            tags = {"External Services"},
            parameters = {
                    @ApiParameter(name = "appId", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_APP_ID),
                    @ApiParameter(name = "id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = ExternalServiceData.class))
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> putExternalService(String appId, String serviceId) {
        context.getRequest()
                .body()
                .compose(body -> taskExecutor.submit(() -> {
                    ExternalService service = ProxyUtil.convertToObject(body, ExternalService.class);
                    if (service == null) {
                        throw new IllegalArgumentException("Request body is required");
                    }
                    ResolvedApp resolved = resolveAndAuthorize(appId);
                    requireDynamic(resolved);
                    ExternalService stored = externalServiceService.putExternalService(
                            resolved.descriptor, serviceId, service, resolved.author);
                    return toData(appId, serviceId, stored, false);
                }))
                .onSuccess(data -> context.respond(HttpStatus.OK, data))
                .onFailure(error -> respondError("Can't create or update external service", error));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "DELETE",
            path = "/v1/applications/{appId}/external-services/{id}",
            operationId = "deleteExternalService",
            tags = {"External Services"},
            parameters = {
                    @ApiParameter(name = "appId", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_APP_ID),
                    @ApiParameter(name = "id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = Boolean.class))
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> deleteExternalService(String appId, String serviceId) {
        taskExecutor.submit(() -> {
            ResolvedApp resolved = resolveAndAuthorize(appId);
            requireDynamic(resolved);
            externalServiceService.deleteExternalService(resolved.descriptor, serviceId, resolved.author);

            // Cascade: purge APP-level credentials for the removed scope. USER-level credentials live in
            // individual user buckets and are not swept (known limitation shared with toolsets, §11.6).
            String scopeId = "applications/" + appId + CredentialsLocatorFactory.EXTERNAL_SERVICES_SEPARATOR + serviceId;
            CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(scopeId, context);
            resourceCredentialsService.deleteResourceCredentialsAtLevel(locator, CredentialsLevel.APPLICATION);
            return true;
        }).onSuccess(removed -> context.respond(HttpStatus.OK, removed))
                .onFailure(error -> respondError("Can't delete external service", error));
        return Future.succeededFuture();
    }

    private ResolvedApp resolveAndAuthorize(String appId) {
        Deployment deployment = context.getConfig().selectDeployment(appId);
        if (deployment instanceof Application configApp) {
            // Static-config app: no per-app owner, management is admin-only.
            if (!accessService.hasAdminAccess(context)) {
                throw new PermissionDeniedException("Only administrators can manage external services of config applications");
            }
            return new ResolvedApp(configApp, null, null, true);
        }

        ResourceDescriptor descriptor;
        try {
            descriptor = ResourceDescriptorFactory.fromAnyUrl(
                    "applications/" + UrlUtil.encodePath(appId), encryptionService);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Application not found: " + appId);
        }
        if (!accessService.hasAdminAccess(context) && !accessService.hasWriteAccess(descriptor, context)) {
            throw new PermissionDeniedException("Admin access or write permission on the application is required");
        }
        Pair<ResourceItemMetadata, Application> result = applicationService.getApplication(descriptor);
        Application application = result.getValue();
        if (application == null) {
            throw new ResourceNotFoundException("Application not found: " + appId);
        }
        return new ResolvedApp(application, descriptor, result.getKey().getAuthor(), false);
    }

    private static void requireDynamic(ResolvedApp resolved) {
        if (resolved.staticApp) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External services of config applications are managed via the configuration file, not the API");
        }
    }

    private static ExternalService getService(Application application, String serviceId) {
        ExternalService service = application.getExternalServices() == null
                ? null : application.getExternalServices().get(serviceId);
        if (service == null) {
            throw new ResourceNotFoundException("External service '%s' not found".formatted(serviceId));
        }
        return service;
    }

    private ExternalServiceData toData(String appId, String serviceId, ExternalService service, boolean withStatus) {
        ResourceAuthSettings authSettings = service.getAuthSettings();
        // Copy and strip secrets — responses must never expose client_secret/code_verifier (encrypted or not).
        ResourceAuthSettings safe = authSettings == null ? null
                : authSettings.toBuilder().clientSecret(null).codeVerifier(null).build();
        if (withStatus && safe != null) {
            String scopeId = "applications/" + appId + CredentialsLocatorFactory.EXTERNAL_SERVICES_SEPARATOR + serviceId;
            CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(scopeId, context);
            resourceAuthSettingsService.setExternalServiceAuthStatuses(locator, safe, context.getUserId());
        }
        return new ExternalServiceData()
                .setId(serviceId)
                .setDisplayName(service.getDisplayName())
                .setDescription(service.getDescription())
                .setAuthSettings(safe);
    }

    private void respondError(String message, Throwable error) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String body = null;
        switch (error) {
            case HttpException e -> {
                status = e.getStatus();
                body = e.getMessage();
            }
            case ResourceNotFoundException e -> {
                status = HttpStatus.NOT_FOUND;
                body = e.getMessage();
            }
            case PermissionDeniedException e -> {
                status = HttpStatus.FORBIDDEN;
                body = e.getMessage();
            }
            case IllegalArgumentException e -> {
                status = HttpStatus.BAD_REQUEST;
                body = e.getMessage();
            }
            case null, default -> log.warn(message, error);
        }
        context.respond(status, body);
    }

    private record ResolvedApp(Application application, ResourceDescriptor descriptor, String author, boolean staticApp) {
    }
}
