package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ExternalServiceData;
import com.epam.aidial.core.server.log.ExternalServiceAuditLog;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.service.ExternalServiceStatusEnricher;
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
import java.util.function.Function;

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
    private final ExternalServiceStatusEnricher statusEnricher;

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
        this.statusEnricher = new ExternalServiceStatusEnricher(context, resourceAuthSettingsService);
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
                            body = @ApiSchema(implementation = List.class, typeArguments = {ExternalServiceData.class})),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> listExternalServices(String appId) {
        taskExecutor.submit(() -> {
            ResolvedApp resolved = resolveApp(appId, true);
            Map<String, ExternalService> services = manageableServices(resolved, appId);
            List<ExternalServiceData> result = new ArrayList<>();
            services.forEach((id, service) -> result.add(toData(appId, id, service, true, resolved.secretsPlain)));
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
                            body = @ApiSchema(implementation = ExternalServiceData.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> getExternalService(String appId, String serviceId) {
        taskExecutor.submit(() -> {
            ResolvedApp resolved = resolveApp(appId, true);
            ExternalService service = manageableService(resolved, appId, serviceId);
            return toData(appId, serviceId, service, true, resolved.secretsPlain);
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
        if (!inline) {
            requireUserAuthoringAllowed(resolved);
        }
        // Overlay user-authored services only for a real user (mirrors the read-overlay guards); writes reject null owners.
        if (resolved.application.isAllowUserExternalServices() && context.getUserId() != null) {
            userExternalServiceService.list(context.getUserId(), appId).forEach(services::putIfAbsent);
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
                            body = @ApiSchema(implementation = ExternalServiceData.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> putExternalService(String appId, String serviceId) {
        context.getRequest()
                .body()
                .compose(body -> taskExecutor.submit(() -> {
                    ExternalService service = ProxyUtil.convertToObject(body, ExternalService.class);
                    if (service == null) {
                        throw new IllegalArgumentException("Request body is required");
                    }
                    ResolvedApp resolved = resolveApp(appId, false);
                    if (canManageInline(resolved)) {
                        requireDynamic(resolved);
                        ExternalService stored = externalServiceService.putExternalService(
                                resolved.descriptor, serviceId, service, resolved.author);
                        return withStoredSecretHint(toData(appId, serviceId, stored, false, false), stored);
                    }
                    requireUserAuthoringAllowed(resolved);
                    ExternalService stored = userExternalServiceService.put(
                            context.getUserId(), appId, serviceId, service, context.getUserDisplayName());
                    return withStoredSecretHint(toData(appId, serviceId, stored, false, false), stored);
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
                            body = @ApiSchema(implementation = Boolean.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> deleteExternalService(String appId, String serviceId) {
        taskExecutor.submit(() -> {
            ResolvedApp resolved = resolveApp(appId, false);
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
        CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(scopeId(appId, serviceId), context);
        resourceCredentialsService.deleteResourceCredentialsAtLevel(locator, level);
    }

    // appId/serviceId arrive already url-decoded (ControllerSelector); re-encode so fromExternalServiceScope's
    // single decode round-trips them exactly, instead of decoding a decoded value a second time.
    private static String scopeId(String appId, String serviceId) {
        return CredentialsLocatorFactory.APPLICATIONS_PREFIX + UrlUtil.encodePath(appId)
                + CredentialsLocatorFactory.EXTERNAL_SERVICES_SEPARATOR + UrlUtil.encodePath(serviceId);
    }

    // decryptSecrets is for read responses that want the client_secret_hint; write and consent paths
    // re-read the blob themselves and have no use for the plaintext.
    private ResolvedApp resolveApp(String appId, boolean decryptSecrets) {
        // See ExternalServiceCredentialsController.resolveApplication: appId omits the "applications/"
        // type prefix, so resolve verbatim first, then by canonical id so a platform-bucket app hits
        // its materialized in-memory entry ("applications/platform/my-app") — with decrypted secrets —
        // instead of being read from blob. A config-managed (platform) app resolves as a static app,
        // access-controlled by its userRoles like a config-file app rather than by folder rules.
        Deployment deployment = context.getConfig().selectDeployment(appId);
        if (deployment == null) {
            deployment = context.getConfig().selectDeployment(CredentialsLocatorFactory.APPLICATIONS_PREFIX + appId);
        }
        if (deployment instanceof Application configApp) {
            // Config apps live in the merged Config, which already holds plaintext secrets.
            return new ResolvedApp(configApp, null, null, true, true);
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
        // Blob-stored secrets are ciphertext; decrypt so read responses can derive the client_secret_hint.
        // Per-service: one undecryptable definition loses only its own hint.
        if (decryptSecrets) {
            externalServiceService.decryptSecretsForResponse(descriptor, application);
        }
        boolean secretsPlain = decryptSecrets;
        return new ResolvedApp(application, descriptor, result.getKey().getAuthor(), false, secretsPlain);
    }

    private boolean canManageInline(ResolvedApp resolved) {
        if (resolved.staticApp) {
            return accessService.hasAdminAccess(context);
        }
        return accessService.hasAdminAccess(context) || accessService.hasWriteAccess(resolved.descriptor, context);
    }

    // A non-inline caller may manage user-authored services only on an app it can at least read, and only when the
    // app opts in. Read access is checked first so a caller with no access can't tell the flag's state (or manage
    // services on an app it can't even see) — a uniform 403 instead of a 200/403-vs-flag oracle.
    private void requireUserAuthoringAllowed(ResolvedApp resolved) {
        boolean readable = resolved.staticApp
                ? resolved.application.hasAccess(context.getUserRoles())
                : accessService.hasReadAccess(resolved.descriptor, context);
        if (!readable) {
            throw new PermissionDeniedException("No read access to application");
        }
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

    private ExternalServiceData toData(String appId, String serviceId, ExternalService service, boolean withStatus,
                                       boolean revealHint) {
        ResourceAuthSettings authSettings = service.getAuthSettings();
        // Responses must never expose client_secret/code_verifier (encrypted or not). Every service reachable
        // through this controller is one the caller may manage (manageableService/manageableServices enforce it),
        // so the client_secret_hint is safe here whenever the secret at hand is plaintext.
        ResourceAuthSettings safe = authSettings == null ? null : authSettings.withoutSecrets(revealHint);
        if (withStatus && safe != null) {
            CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(scopeId(appId, serviceId), context);
            statusEnricher.enrich(locator, safe);
        }
        return new ExternalServiceData()
                .setId(serviceId)
                .setDisplayName(service.getDisplayName())
                .setDescription(service.getDescription())
                .setAuthSettings(safe);
    }

    // The stored copy holds the encrypted secret by the time it comes back, so the hint is the one the write
    // service stamped for the value it actually persisted — including a secret preserved from a request that
    // omitted it, which the response would otherwise report as unconfigured.
    private static ExternalServiceData withStoredSecretHint(ExternalServiceData data, ExternalService stored) {
        if (data.getAuthSettings() != null && stored.getAuthSettings() != null) {
            data.getAuthSettings().setClientSecretHint(stored.getAuthSettings().getClientSecretHint());
        }
        return data;
    }

    private void respondError(String message, Throwable error) {
        ExternalServiceErrorHandler.respond(context, message, error);
    }

    /**
     * An administrator approves this application's use of a DIAL-native service. Applies to every user who has
     * offline credentials, not only those who opted into this application.
     */
    @ApiOperation(
            method = "POST",
            path = "/v1/applications/{appId}/external-services/{id}/consent",
            operationId = "grantExternalServiceConsent",
            tags = {"External Services"},
            parameters = {
                    @ApiParameter(name = "appId", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_APP_ID),
                    @ApiParameter(name = "id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = Boolean.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> grantConsent(String appId, String serviceId) {
        return consentOperation(appId, serviceId, "GRANT", "Can't grant consent", service -> {
            CredentialsDescriptor descriptor = consentDescriptor(appId, serviceId);
            // The record's existence is the approval; who granted it is in the audit event, which keeps history.
            resourceCredentialsService.putCredentialsRecord(descriptor, ResourceCredentials.builder()
                    .resourceId(descriptor.getResourceId())
                    .credentialsLevel(CredentialsLevel.APPLICATION)
                    .authenticationType(service.getAuthSettings().getAuthenticationType())
                    .build());
            return true;
        });
    }

    /** Withdraws the approval. The application stops working for every user immediately. */
    @ApiOperation(
            method = "DELETE",
            path = "/v1/applications/{appId}/external-services/{id}/consent",
            operationId = "withdrawExternalServiceConsent",
            tags = {"External Services"},
            parameters = {
                    @ApiParameter(name = "appId", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_APP_ID),
                    @ApiParameter(name = "id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.EXTERNAL_SERVICE_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = Boolean.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> withdrawConsent(String appId, String serviceId) {
        return consentOperation(appId, serviceId, "WITHDRAW", "Can't withdraw consent",
                service -> resourceCredentialsService.deleteCredentialsRecord(consentDescriptor(appId, serviceId)));
    }

    /** Both consent operations are the same act with a different verb: admin only, and audited either way. */
    private Future<?> consentOperation(String appId, String serviceId, String action, String errorMessage,
                                       Function<ExternalService, Boolean> operation) {
        taskExecutor.submit(() -> {
            requireAdmin();
            return operation.apply(resolveDialNativeService(appId, serviceId));
        })
                .onComplete(result -> ExternalServiceAuditLog.consent(
                        context, appId, serviceId, action, ExternalServiceErrorHandler.asRuntime(result.cause())))
                .onSuccess(applied -> context.respond(HttpStatus.OK, applied))
                .onFailure(error -> respondError(errorMessage, error));
        return Future.succeededFuture();
    }

    /** Consent is meaningful only for DIAL-native services; other types are authorized by a stored credential. */
    private ExternalService resolveDialNativeService(String appId, String serviceId) {
        ResolvedApp resolved = resolveApp(appId, false);
        ExternalService service = resolved.application.getExternalServices() == null
                ? null : resolved.application.getExternalServices().get(serviceId);
        if (service == null || service.getAuthSettings() == null) {
            throw new ResourceNotFoundException(
                    "External service '%s' is not defined for application '%s'".formatted(serviceId, appId));
        }
        if (service.getAuthSettings().getAuthenticationType() != AuthenticationType.DIAL_NATIVE) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Consent applies only to %s services".formatted(AuthenticationType.DIAL_NATIVE));
        }
        return service;
    }

    /**
     * Administrators only — an app owner approving their own application would be granting it the right to act as
     * every user with offline credentials. Checked before the service is resolved, so 403 leaks nothing.
     */
    private void requireAdmin() {
        if (!accessService.hasAdminAccess(context)) {
            throw new PermissionDeniedException("Only administrators may consent to a DIAL-native external service");
        }
    }

    private CredentialsDescriptor consentDescriptor(String appId, String serviceId) {
        // scopeId encodes both parts: the ids arrive decoded, and fromExternalServiceScope decodes again.
        CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(scopeId(appId, serviceId), context);
        CredentialsDescriptor descriptor = locator.getCredentialsDescriptors().get(CredentialsLevel.APPLICATION);
        if (descriptor == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Application-level consent is not supported for: " + appId);
        }
        return descriptor;
    }

    private record ResolvedApp(Application application, ResourceDescriptor descriptor, String author, boolean staticApp,
                               boolean secretsPlain) {
    }
}
