package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ExternalServiceData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.UserExternalServiceService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin/app-owner CRUD for an application's external-service definitions; static-config apps are read-only. */
@Slf4j
public class ExternalServiceManagementController {

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final ApplicationService applicationService;
    private final ExternalServiceService externalServiceService;
    private final UserExternalServiceService userExternalServiceService;
    private final AccessService accessService;
    private final EncryptionService encryptionService;
    private final ResourceCredentialsService resourceCredentialsService;
    private final ResourceAuthSettingsService resourceAuthSettingsService;

    public ExternalServiceManagementController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.applicationService = proxy.getApplicationService();
        this.externalServiceService = proxy.getExternalServiceService();
        this.userExternalServiceService = proxy.getUserExternalServiceService();
        this.accessService = proxy.getAccessService();
        this.encryptionService = proxy.getEncryptionService();
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
        this.resourceAuthSettingsService = proxy.getResourceAuthSettingsService();
    }

    public Future<?> listExternalServices(String appId) {
        taskExecutor.submit(() -> {
            ResolvedApp resolved = resolveApp(appId);
            Map<String, ExternalService> services = manageableServices(resolved, appId);
            List<ExternalServiceData> result = new ArrayList<>();
            services.forEach((id, service) -> result.add(toData(appId, id, service, true)));
            return result;
        }).onSuccess(result -> context.respond(HttpStatus.OK, result))
                .onFailure(error -> respondError("Can't list external services", error));
        return Future.succeededFuture();
    }

    public Future<?> getExternalService(String appId, String serviceId) {
        taskExecutor.submit(() -> {
            ResolvedApp resolved = resolveApp(appId);
            ExternalService service = manageableService(resolved, appId, serviceId);
            return toData(appId, serviceId, service, true);
        }).onSuccess(data -> context.respond(HttpStatus.OK, data))
                .onFailure(error -> respondError("Can't get external service", error));
        return Future.succeededFuture();
    }

    // Callers see inline (admin) definitions when they can manage them, unioned with their own user-authored
    // ones (inline wins on id clash) — so a service authored before gaining write access stays visible.
    private Map<String, ExternalService> manageableServices(ResolvedApp resolved, String appId) {
        Map<String, ExternalService> services = new LinkedHashMap<>();
        boolean inline = canManageInline(resolved);
        if (inline && resolved.application.getExternalServices() != null) {
            services.putAll(resolved.application.getExternalServices());
        }
        // Overlay user-authored services only for a real user (mirrors the read-overlay guards); writes reject null owners.
        if (resolved.application.isAllowUserExternalServices()) {
            if (context.getUserId() != null) {
                userExternalServiceService.list(context.getUserId(), appId).forEach(services::putIfAbsent);
            }
        } else if (!inline) {
            throw new PermissionDeniedException("This application does not allow user-authored external services");
        }
        return services;
    }

    private ExternalService manageableService(ResolvedApp resolved, String appId, String serviceId) {
        if (canManageInline(resolved)) {
            ExternalService inline = resolved.application.getExternalServices() == null
                    ? null : resolved.application.getExternalServices().get(serviceId);
            if (inline != null) {
                return inline;
            }
        } else {
            requireUserAuthoringAllowed(resolved);
        }
        if (resolved.application.isAllowUserExternalServices() && context.getUserId() != null) {
            ExternalService service = userExternalServiceService.get(context.getUserId(), appId, serviceId);
            if (service != null) {
                return service;
            }
        }
        throw new ResourceNotFoundException("External service '%s' not found".formatted(serviceId));
    }

    public Future<?> putExternalService(String appId, String serviceId) {
        context.getRequest()
                .body()
                .compose(body -> taskExecutor.submit(() -> {
                    ExternalService service = ProxyUtil.convertToObject(body, ExternalService.class);
                    if (service == null) {
                        throw new IllegalArgumentException("Request body is required");
                    }
                    ResolvedApp resolved = resolveApp(appId);
                    if (canManageInline(resolved)) {
                        requireDynamic(resolved);
                        ExternalService stored = externalServiceService.putExternalService(
                                resolved.descriptor, serviceId, service, resolved.author);
                        return toData(appId, serviceId, stored, false);
                    }
                    requireUserAuthoringAllowed(resolved);
                    ExternalService stored = userExternalServiceService.put(
                            context.getUserId(), appId, serviceId, service, context.getUserDisplayName());
                    return toData(appId, serviceId, stored, false);
                }))
                .onSuccess(data -> context.respond(HttpStatus.OK, data))
                .onFailure(error -> respondError("Can't create or update external service", error));
        return Future.succeededFuture();
    }

    public Future<?> deleteExternalService(String appId, String serviceId) {
        taskExecutor.submit(() -> {
            ResolvedApp resolved = resolveApp(appId);
            if (canManageInline(resolved)) {
                requireDynamic(resolved);
                externalServiceService.deleteExternalService(resolved.descriptor, serviceId, resolved.author);

                // Cascade: purge APP-level credentials for the removed scope. USER-level credentials live in
                // individual user buckets and are not swept (known limitation shared with toolsets).
                purgeCredentials(appId, serviceId, CredentialsLevel.APPLICATION);
                return true;
            }
            requireUserAuthoringAllowed(resolved);
            userExternalServiceService.delete(context.getUserId(), appId, serviceId);
            // Cascade: the author owns both the definition and its USER-level credentials, so purge them too.
            purgeCredentials(appId, serviceId, CredentialsLevel.USER);
            return true;
        }).onSuccess(removed -> context.respond(HttpStatus.OK, removed))
                .onFailure(error -> respondError("Can't delete external service", error));
        return Future.succeededFuture();
    }

    private void purgeCredentials(String appId, String serviceId, CredentialsLevel level) {
        String scopeId = CredentialsLocatorFactory.APPLICATIONS_PREFIX + appId
                + CredentialsLocatorFactory.EXTERNAL_SERVICES_SEPARATOR + serviceId;
        CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(scopeId, context);
        resourceCredentialsService.deleteResourceCredentialsAtLevel(locator, level);
    }

    private ResolvedApp resolveApp(String appId) {
        Deployment deployment = context.getConfig().selectDeployment(appId);
        if (deployment instanceof Application configApp) {
            return new ResolvedApp(configApp, null, null, true);
        }
        ResourceDescriptor descriptor;
        try {
            descriptor = ResourceDescriptorFactory.fromAnyUrl(
                    CredentialsLocatorFactory.APPLICATIONS_PREFIX + UrlUtil.encodePath(appId), encryptionService);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Application not found: " + appId);
        }
        Pair<ResourceItemMetadata, Application> result = applicationService.getApplication(descriptor);
        Application application = result.getValue();
        if (application == null) {
            throw new ResourceNotFoundException("Application not found: " + appId);
        }
        return new ResolvedApp(application, descriptor, result.getKey().getAuthor(), false);
    }

    private boolean canManageInline(ResolvedApp resolved) {
        if (resolved.staticApp) {
            return accessService.hasAdminAccess(context);
        }
        return accessService.hasAdminAccess(context) || accessService.hasWriteAccess(resolved.descriptor, context);
    }

    private void requireUserAuthoringAllowed(ResolvedApp resolved) {
        if (!resolved.application.isAllowUserExternalServices()) {
            throw new PermissionDeniedException("This application does not allow user-authored external services");
        }
    }

    private static void requireDynamic(ResolvedApp resolved) {
        if (resolved.staticApp) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External services of config applications are managed via the configuration file, not the API");
        }
    }

    private ExternalServiceData toData(String appId, String serviceId, ExternalService service, boolean withStatus) {
        ResourceAuthSettings authSettings = service.getAuthSettings();
        // Responses must never expose client_secret/code_verifier (encrypted or not).
        ResourceAuthSettings safe = authSettings == null ? null : authSettings.withoutSecrets();
        if (withStatus && safe != null) {
            String scopeId = CredentialsLocatorFactory.APPLICATIONS_PREFIX + appId
                    + CredentialsLocatorFactory.EXTERNAL_SERVICES_SEPARATOR + serviceId;
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
