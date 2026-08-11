package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignOutRequest;
import com.epam.aidial.core.credentials.service.AuthorizationHeaderProvider;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiOperations;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ExternalServiceCredentialsRequest;
import com.epam.aidial.core.server.data.ExternalServiceCredentialsResponse;
import com.epam.aidial.core.server.data.OboCredentialsRequest;
import com.epam.aidial.core.server.log.ExternalServiceAuditLog;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.AccessTokenValidator;
import com.epam.aidial.core.server.security.AppIdentityMatcher;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.security.IdentityProvider;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ConsentRequiredException;
import com.epam.aidial.core.server.service.OfflineCredentialsRequiredException;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.UserExternalServiceService;
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
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

@Slf4j
public class ExternalServiceCredentialsController {

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final ResourceCredentialsService resourceCredentialsService;
    private final AuthorizationHeaderProvider authorizationHeaderProvider;
    private final AccessService accessService;
    private final EncryptionService encryptionService;
    private final ApplicationService applicationService;
    private final UserExternalServiceService userExternalServiceService;
    private final AccessTokenValidator accessTokenValidator;

    public ExternalServiceCredentialsController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.accessService = proxy.getAccessService();
        this.encryptionService = proxy.getEncryptionService();
        this.applicationService = proxy.getApplicationService();
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
        this.authorizationHeaderProvider = proxy.getAuthorizationHeaderProvider();
        this.userExternalServiceService = proxy.getUserExternalServiceService();
        this.accessTokenValidator = proxy.getTokenValidator();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/external-service/signin",
            operationId = "externalServiceSignIn",
            requestBody = @ApiSchema(implementation = ResourceSignInRequest.class),
            tags = {"External Services"},
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = Boolean.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> signIn() {
        if (context.getApiKeyData().getPerRequestKey() != null) {
            context.respond(HttpStatus.UNAUTHORIZED, "Sign-in cannot be invoked with a per-request key");
            return Future.succeededFuture();
        }
        context.getRequest()
                .body()
                .compose(body -> {
                    ResourceSignInRequest request = ProxyUtil.convertToObject(body, ResourceSignInRequest.class);
                    ValidationUtil.validate(request);
                    return taskExecutor.submit(() -> {
                        ResolvedExternalService resolved = resolveExternalService(request.getUrl());
                        ResourceAuthSettings authSettings = resolved.externalService.getAuthSettings();
                        validateAuthType(authSettings.getAuthenticationType(), request.getAuthenticationType());
                        rejectDialNativeSignIn(authSettings.getAuthenticationType());

                        verifyAccess(resolved, request.getCredentialsLevel());

                        CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(request.getUrl(), context);
                        CredentialsDescriptor descriptor = locator.getCredentialsDescriptors().get(request.getCredentialsLevel());
                        if (descriptor == null) {
                            throw new IllegalArgumentException("Unsupported credentials_level: " + request.getCredentialsLevel());
                        }
                        // The CredentialsLocator's resourceId is the normalized storage path; the
                        // sign-in request still carries the caller's URL — keep it consistent so the
                        // factory writes credentials at the locator's path.
                        ResourceSignInRequest normalized = ResourceSignInRequest.builder()
                                .url(locator.getResourceId())
                                .credentialsLevel(request.getCredentialsLevel())
                                .authenticationType(request.getAuthenticationType())
                                .code(request.getCode())
                                .apiKey(request.getApiKey())
                                .redirectUri(request.getRedirectUri())
                                .offlineUsageConsent(request.isOfflineUsageConsent())
                                .build();
                        resourceCredentialsService.addResourceCredentials(descriptor, authSettings, normalized, context.getUserId());
                        return true;
                    });
                })
                .onSuccess(added -> context.respond(HttpStatus.OK, added))
                .onFailure(error -> respondError("Can't signIn into external service", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/external-service/signout",
            operationId = "externalServiceSignOut",
            requestBody = @ApiSchema(implementation = ResourceSignOutRequest.class),
            tags = {"External Services"},
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = Boolean.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> signOut() {
        if (context.getApiKeyData().getPerRequestKey() != null) {
            context.respond(HttpStatus.UNAUTHORIZED, "Sign-out cannot be invoked with a per-request key");
            return Future.succeededFuture();
        }
        context.getRequest()
                .body()
                .compose(body -> taskExecutor.submit(() -> {
                    ResourceSignOutRequest request = ProxyUtil.convertToObject(body, ResourceSignOutRequest.class);
                    ValidationUtil.validate(request);

                    ResolvedExternalService resolved = resolveExternalService(request.getUrl());
                    ResourceAuthSettings authSettings = resolved.externalService.getAuthSettings();
                    validateAuthType(authSettings.getAuthenticationType(), request.getAuthenticationType());
                    rejectDialNativeSignOut(authSettings.getAuthenticationType());
                    verifyAccess(resolved, request.getCredentialsLevel());

                    CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(request.getUrl(), context);
                    ResourceSignOutRequest normalized = ResourceSignOutRequest.builder()
                            .url(locator.getResourceId())
                            .credentialsLevel(request.getCredentialsLevel())
                            .authenticationType(request.getAuthenticationType())
                            .build();
                    return resourceCredentialsService.deleteResourceCredentials(locator, normalized, context.getUserId());
                }))
                .onSuccess(removed -> context.respond(HttpStatus.OK, removed))
                .onFailure(error -> respondError("Can't signOut from external service", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/external-service/credentials",
            operationId = "externalServiceGetCredentials",
            requestBody = @ApiSchema(implementation = ExternalServiceCredentialsRequest.class),
            tags = {"External Services"},
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = ExternalServiceCredentialsResponse.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> getCredentials() {
        if (context.getApiKeyData().getPerRequestKey() == null) {
            context.respond(HttpStatus.UNAUTHORIZED, "Per-request key is required");
            return Future.succeededFuture();
        }

        context.getRequest()
                .body()
                .compose(body -> taskExecutor.submit(() -> {
                    ExternalServiceCredentialsRequest request = ProxyUtil.convertToObject(body, ExternalServiceCredentialsRequest.class);
                    ValidationUtil.validate(request);

                    String[] parts = CredentialsLocatorFactory.parseExternalServiceScope(request.getUrl());
                    String appPart = parts[0];

                    String callerApp = context.getDecodedSourceDeployment();
                    // Static config deployments expose getName() as the bare app id; dynamic
                    // application deployments expose getName() as the full "applications/{...}" URL.
                    // Normalize so both shapes compare against the parsed appPart.
                    String normalizedCaller = callerApp;
                    if (normalizedCaller != null && normalizedCaller.startsWith(CredentialsLocatorFactory.APPLICATIONS_PREFIX)) {
                        normalizedCaller = normalizedCaller.substring(CredentialsLocatorFactory.APPLICATIONS_PREFIX.length());
                    }
                    if (normalizedCaller == null || !normalizedCaller.equals(appPart)) {
                        throw new PermissionDeniedException("Per-request key is not bound to application: " + appPart);
                    }

                    ResolvedExternalService resolved = resolveExternalService(request.getUrl());
                    ResourceAuthSettings authSettings = resolved.externalService.getAuthSettings();

                    CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(request.getUrl(), context);
                    ResourceCredentials credentials = resourceCredentialsService.getRefreshedResourceCredentials(
                            locator, authSettings, context.getUserId());

                    return toCredentialsResponse(credentials, request.getUrl());
                }))
                .onSuccess(response -> context.respond(HttpStatus.OK, response))
                .onFailure(error -> respondError("Can't get external service credentials", error));

        return Future.succeededFuture();
    }

    /**
     * On-behalf-of (OBO) credential retrieval: a trusted background actor (e.g. the Scheduler) retrieves
     * an absent owner's external-service credential without an active user request. The caller's own
     * identity (a DIAL key or an OAuth workload JWT) must match the owning app's {@code app_identity};
     * gated also by the owner's recorded offline-usage consent and resolved USER-level only (fail closed).
     * Per-request keys are forbidden — that is the live-request {@code /credentials} path.
     */
    @ApiOperation(
            method = "POST",
            path = "/v1/ops/external-service/obo-credentials",
            operationId = "externalServiceGetOboCredentials",
            requestBody = @ApiSchema(implementation = OboCredentialsRequest.class),
            tags = {"External Services"},
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = ExternalServiceCredentialsResponse.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> getOboCredentials() {
        if (context.getApiKeyData().getPerRequestKey() != null) {
            context.respond(HttpStatus.UNAUTHORIZED, "On-behalf-of retrieval cannot be invoked with a per-request key");
            return Future.succeededFuture();
        }

        context.getRequest()
                .body()
                .compose(body -> taskExecutor.submit(() -> {
                    OboCredentialsRequest request = ProxyUtil.convertToObject(body, OboCredentialsRequest.class);
                    ValidationUtil.validate(request);

                    try {
                        String[] scope = CredentialsLocatorFactory.parseExternalServiceScope(request.getUrl());

                        // Gate on app_identity before resolving the owner-scoped service, so an untrusted caller
                        // gets a uniform 403 with no owner-bucket read (no 404-vs-403 enumeration oracle).
                        ResolvedApplication app = resolveApplication(scope[0]);
                        if (!AppIdentityMatcher.matches(context, app.application.getAppIdentity())) {
                            throw new PermissionDeniedException("Caller identity does not match the application's app_identity");
                        }

                        ExternalService externalService = resolveExternalServiceDefinition(
                                app.application, scope[0], scope[1], request.getOwnerUserId());
                        ResourceAuthSettings authSettings = externalService.getAuthSettings();

                        if (AuthenticationType.DIAL_NATIVE.equals(authSettings.getAuthenticationType())) {
                            ExternalServiceCredentialsResponse dialNative =
                                    redeemOfflineCredentials(request, scope[0], scope[1]);
                            ExternalServiceAuditLog.oboRetrieval(context, scope[0], scope[1], request.getOwnerUserId(), null);
                            return dialNative;
                        }

                        CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScopeForOwner(
                                request.getUrl(), request.getOwnerUserId(), context);
                        ResourceCredentials credentials = resourceCredentialsService.getRefreshedUserCredentials(
                                locator, authSettings, request.getOwnerUserId());

                        // Fail closed: no fallback to APP/GLOBAL. Missing owner credential ⇒ 404.
                        if (credentials == null) {
                            throw new ResourceNotFoundException("Credentials for %s not found".formatted(request.getUrl()));
                        }
                        if (!credentials.isOfflineUsageConsent()) {
                            throw new ConsentRequiredException("Offline usage consent required for on-behalf-of retrieval");
                        }

                        ExternalServiceCredentialsResponse response = toCredentialsResponse(credentials, request.getUrl());
                        ExternalServiceAuditLog.oboRetrieval(context, scope[0], scope[1], request.getOwnerUserId(), null);
                        return response;
                    } catch (RuntimeException e) {
                        // Audit failures too — including a malformed url that fails scope parsing (best-effort ids).
                        String[] scope = safeParseScope(request.getUrl());
                        ExternalServiceAuditLog.oboRetrieval(context, scope[0], scope[1], request.getOwnerUserId(), e);
                        throw e;
                    }
                }))
                .onSuccess(response -> context.respond(HttpStatus.OK, response))
                .onFailure(error -> respondError("Can't get on-behalf-of external service credentials", error));

        return Future.succeededFuture();
    }

    /**
     * Redemption for a DIAL-native service: the owner acts through their own offline credentials, and the
     * application is authorized by an administrator's consent rather than by anything it holds.
     */
    private ExternalServiceCredentialsResponse redeemOfflineCredentials(OboCredentialsRequest request,
                                                                        String appPart,
                                                                        String serviceId) {
        requireAdminConsent(request.getUrl(), appPart, serviceId);

        CredentialsDescriptor descriptor =
                CredentialsDescriptorFactory.offlineCredentialsForUser(context, request.getOwnerUserId());
        ResourceCredentials stored = resourceCredentialsService.getResourceCredentials(descriptor);
        if (stored == null) {
            throw new OfflineCredentialsRequiredException(
                    "The owner has not enabled offline access, so nothing can act on their behalf");
        }

        ResourceAuthSettings offlineClient = resolveOfflineClient(stored.getIssuer());

        CredentialsLocator locator = new CredentialsLocator(descriptor.getResourceId(),
                Map.of(CredentialsLevel.USER, new BucketInfo(descriptor.getBucketName(), descriptor.getBucketLocation())));
        ResourceCredentials credentials;
        try {
            credentials = resourceCredentialsService.getRefreshedUserCredentials(
                    locator, offlineClient, request.getOwnerUserId());
        } catch (HttpException e) {
            throw translateRefreshFailure(e);
        }
        if (credentials == null) {
            throw new OfflineCredentialsRequiredException(
                    "The owner's offline credentials are no longer valid; they must connect again");
        }
        // getRefreshedUserCredentials returns the record un-refreshed without consent, leaving the refusal here.
        if (!credentials.isOfflineUsageConsent()) {
            throw new OfflineCredentialsRequiredException(
                    "The owner's credentials do not permit offline use; they must connect again");
        }
        return toCredentialsResponse(credentials, request.getUrl());
    }

    /**
     * The offline client of the identity provider that issued the stored credentials. Both failures are server-side
     * state — a provider dropped from the settings, or one never configured for offline use — not a bad request.
     */
    private ResourceAuthSettings resolveOfflineClient(String issuer) {
        IdentityProvider provider;
        try {
            provider = accessTokenValidator.resolveProviderByIssuer(issuer);
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The identity provider that issued the owner's offline credentials is no longer configured");
        }
        ResourceAuthSettings offlineClient = provider.getOfflineClient();
        if (offlineClient == null) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Offline credentials are not configured for the identity provider that issued them");
        }
        return offlineClient;
    }

    /** An administrator must have approved this application's use of the service; declaring it grants nothing. */
    private void requireAdminConsent(String url, String appPart, String serviceId) {
        CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(url, context);
        CredentialsDescriptor consent = locator.getCredentialsDescriptors().get(CredentialsLevel.APPLICATION);
        ResourceCredentials record = consent == null ? null : resourceCredentialsService.getResourceCredentials(consent);
        // Only a record the consent endpoint wrote counts. The same APPLICATION-level slot holds ordinary
        // credentials while a service is OAUTH/API_KEY, and a leftover from before a switch to DIAL_NATIVE
        // would otherwise pass as an administrator's approval the app owner granted themselves.
        if (record == null || !AuthenticationType.DIAL_NATIVE.equals(record.getAuthenticationType())) {
            throw new ConsentRequiredException(
                    "Application '%s' is not approved to use '%s'".formatted(appPart, serviceId));
        }
    }

    /**
     * Keeps a permanent failure distinguishable from a retryable one. A 401 here means the <i>owner's</i> token was
     * rejected, not the caller's, so it must not surface as one.
     */
    private RuntimeException translateRefreshFailure(HttpException e) {
        if (e.getStatus() == HttpStatus.UNAUTHORIZED) {
            return new OfflineCredentialsRequiredException(
                    "The owner's offline credentials were rejected by the identity provider; they must connect again");
        }
        if (e.getStatus().getCode() >= 500) {
            return new HttpException(HttpStatus.BAD_GATEWAY,
                    "The identity provider could not be reached; this run may be retried");
        }
        return e;
    }

    private ExternalServiceCredentialsResponse toCredentialsResponse(ResourceCredentials credentials, String url) {
        AuthorizationHeader header = authorizationHeaderProvider.createAuthorizationHeader(credentials);
        if (header == null) {
            throw new ResourceNotFoundException("Credentials for %s not found".formatted(url));
        }
        ExternalServiceCredentialsResponse response = new ExternalServiceCredentialsResponse()
                .setHeaderName(header.getHeaderName())
                .setHeaderValue(header.getHeaderValue());
        if (AuthenticationType.OAUTH.equals(credentials.getAuthenticationType())
                && credentials.getExpiresInSeconds() != null) {
            // updatedAt is stored as Unix epoch milliseconds (TimeProvider#getCurrentTime()), expiresInSeconds is
            // seconds; the spec mandates Unix epoch seconds.
            response.setExpiresAt(credentials.getUpdatedAt() / 1000L + credentials.getExpiresInSeconds());
        }
        return response;
    }

    // Never throws: yields {null, null} when the url can't be parsed, so a malformed request is still audited.
    private static String[] safeParseScope(String url) {
        try {
            return CredentialsLocatorFactory.parseExternalServiceScope(url);
        } catch (RuntimeException e) {
            return new String[]{null, null};
        }
    }

    // Caller-scoped resolution: the caller is the credential owner.
    private ResolvedExternalService resolveExternalService(String scopeId) {
        return resolveExternalService(scopeId, null);
    }

    /**
     * Canonical external-service definition resolver. Resolves the admin/inline definition first; when the
     * app permits it ({@code allow_user_external_services}) and no inline definition exists, falls back to a
     * user-authored definition in the owner's bucket. {@code ownerUserId} is the credential
     * owner on the OBO path; {@code null} on caller-scoped paths, where the caller is the owner.
     */
    private ResolvedExternalService resolveExternalService(String scopeId, @Nullable String ownerUserId) {
        String[] parts = CredentialsLocatorFactory.parseExternalServiceScope(scopeId);
        ResolvedApplication app = resolveApplication(parts[0]);
        ExternalService externalService = resolveExternalServiceDefinition(app.application, parts[0], parts[1], ownerUserId);
        return new ResolvedExternalService(app.application, externalService, app.applicationDescriptor, app.staticApp);
    }

    /** Resolves only the application for a scope's app part — never the owner's bucket — so the OBO gate can run first. */
    private ResolvedApplication resolveApplication(String appPart) {
        // appPart carries no "applications/" type prefix (the URL is already under /v1/applications/),
        // whereas materialized Config keys do. Resolve verbatim first (config-file bare names, and —
        // once short-name addressing lands — short-name platform apps via derivation), then by
        // canonical id so a platform-bucket reference like "platform/my-app" hits its materialized
        // in-memory entry ("applications/platform/my-app"), which already carries decrypted secrets,
        // instead of being read back from blob. A config-managed (platform) app is access-controlled
        // by its userRoles, so it resolves as a static app and verifyAccess uses hasAccess(userRoles).
        Deployment deployment = context.getConfig().selectDeployment(appPart);
        if (deployment == null) {
            deployment = context.getConfig().selectDeployment(CredentialsLocatorFactory.APPLICATIONS_PREFIX + appPart);
        }
        if (deployment instanceof Application configApp) {
            return new ResolvedApplication(configApp, null, true);
        }
        // Dynamic (public / user-bucket) app — not materialized; read from blob, rule-based access.
        ResourceDescriptor appDescriptor;
        try {
            appDescriptor = ResourceDescriptorFactory.fromAnyUrl(
                    CredentialsLocatorFactory.APPLICATIONS_PREFIX + UrlUtil.encodePath(appPart), encryptionService);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Application not found: " + appPart);
        }
        Application application = applicationService.getApplicationWithDecryptedSecrets(appDescriptor).getValue();
        if (application == null) {
            throw new ResourceNotFoundException("Application not found: " + appPart);
        }
        return new ResolvedApplication(application, appDescriptor, false);
    }

    private ExternalService resolveExternalServiceDefinition(Application application, String appPart,
                                                             String externalServiceId, @Nullable String ownerUserId) {
        ExternalService externalService = application.getExternalServices() == null
                ? null : application.getExternalServices().get(externalServiceId);
        if (externalService == null && application.isAllowUserExternalServices()) {
            String owner = ownerUserId != null ? ownerUserId : context.getUserId();
            if (owner != null) {
                externalService = userExternalServiceService.get(owner, appPart, externalServiceId);
            }
        }
        if (externalService == null) {
            throw new ResourceNotFoundException(
                    "External service '%s' is not defined for application '%s'".formatted(externalServiceId, appPart));
        }
        if (externalService.getAuthSettings() == null) {
            throw new ResourceNotFoundException(
                    "External service '%s' has no auth settings".formatted(externalServiceId));
        }
        return externalService;
    }

    private void verifyAccess(ResolvedExternalService resolved, CredentialsLevel credentialsLevel) {
        if (resolved.staticApp) {
            if (!resolved.application.hasAccess(context.getUserRoles())) {
                throw new PermissionDeniedException("No access to application");
            }
        } else {
            ResourceDescriptor descriptor = resolved.applicationDescriptor;
            Map<ResourceDescriptor, Set<ResourceAccessType>> permissions =
                    accessService.lookupPermissions(Set.of(descriptor), context);
            Set<ResourceAccessType> granted = permissions.get(descriptor);
            if (granted == null || !granted.contains(ResourceAccessType.READ)) {
                throw new PermissionDeniedException("No read access to application");
            }
        }

        if (credentialsLevel == CredentialsLevel.APPLICATION) {
            if (accessService.hasAdminAccess(context)) {
                return;
            }
            if (resolved.staticApp) {
                throw new PermissionDeniedException(
                        "Only administrators can manage APPLICATION-level credentials for static-config applications");
            }
            if (!accessService.hasWriteAccess(resolved.applicationDescriptor, context)) {
                throw new PermissionDeniedException(
                        "APPLICATION-level credentials require admin access or write permission on the application");
            }
        }
    }

    private void validateAuthType(AuthenticationType configured, AuthenticationType requested) {
        if (!Objects.equals(configured, requested)) {
            throw new IllegalArgumentException("Wrong authentication_type. Expected type: %s, provided: %s"
                    .formatted(configured, requested));
        }
    }

    /** A DIAL-native service has no credential to sign in with. */
    private void rejectDialNativeSignIn(AuthenticationType configured) {
        if (AuthenticationType.DIAL_NATIVE.equals(configured)) {
            throw new IllegalArgumentException(("Sign-in is not applicable to %s services: users grant offline access "
                    + "via the offline-credentials endpoints, and an administrator approves the application separately")
                    .formatted(AuthenticationType.DIAL_NATIVE));
        }
    }

    /**
     * A DIAL-native service also has nothing to sign out of: no USER-level record ever exists, and the
     * APPLICATION-level record is the administrator's consent — deletable only through the audited,
     * admin-only consent endpoint, never through the app-owner-accessible generic sign-out.
     */
    private void rejectDialNativeSignOut(AuthenticationType configured) {
        if (AuthenticationType.DIAL_NATIVE.equals(configured)) {
            throw new IllegalArgumentException(("Sign-out is not applicable to %s services: users disconnect via the "
                    + "offline-credentials endpoints, and an administrator withdraws consent via the consent endpoint")
                    .formatted(AuthenticationType.DIAL_NATIVE));
        }
    }

    private void respondError(String message, Throwable error) {
        ExternalServiceErrors.respond(context, message, error);
    }

    private record ResolvedExternalService(Application application,
                                           ExternalService externalService,
                                           ResourceDescriptor applicationDescriptor,
                                           boolean staticApp) {
    }

    private record ResolvedApplication(Application application,
                                       ResourceDescriptor applicationDescriptor,
                                       boolean staticApp) {
    }
}
