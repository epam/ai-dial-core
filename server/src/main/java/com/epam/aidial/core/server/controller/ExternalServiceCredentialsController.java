package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignOutRequest;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import com.epam.aidial.core.credentials.service.AuthorizationHeaderProvider;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ExternalServiceCredentialsRequest;
import com.epam.aidial.core.server.data.ExternalServiceCredentialsResponse;
import com.epam.aidial.core.server.data.OboCredentialsRequest;
import com.epam.aidial.core.server.log.ExternalServiceAuditLog;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.UserExternalServiceService;
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
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    public ExternalServiceCredentialsController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.accessService = proxy.getAccessService();
        this.encryptionService = proxy.getEncryptionService();
        this.applicationService = proxy.getApplicationService();
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
        this.authorizationHeaderProvider = proxy.getAuthorizationHeaderProvider();
        this.userExternalServiceService = proxy.getUserExternalServiceService();
    }

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
                        ResolvedExternalService resolved = resolveExternalService(request.getUrl(), null);
                        ResourceAuthSettings authSettings = resolved.externalService.getAuthSettings();
                        validateAuthType(authSettings.getAuthenticationType(), request.getAuthenticationType());

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

                    ResolvedExternalService resolved = resolveExternalService(request.getUrl(), null);
                    ResourceAuthSettings authSettings = resolved.externalService.getAuthSettings();
                    validateAuthType(authSettings.getAuthenticationType(), request.getAuthenticationType());
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
                    if (normalizedCaller != null && normalizedCaller.startsWith("applications/")) {
                        normalizedCaller = normalizedCaller.substring("applications/".length());
                    }
                    if (normalizedCaller == null || !normalizedCaller.equals(appPart)) {
                        throw new PermissionDeniedException("Per-request key is not bound to application: " + appPart);
                    }

                    ResolvedExternalService resolved = resolveExternalService(request.getUrl(), null);
                    ResourceAuthSettings authSettings = resolved.externalService.getAuthSettings();

                    CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(request.getUrl(), context);
                    ResourceCredentials credentials = resourceCredentialsService.getRefreshedResourceCredentials(
                            locator, authSettings, context.getUserId());

                    AuthorizationHeader header = authorizationHeaderProvider.createAuthorizationHeader(credentials);
                    if (header == null) {
                        throw new ResourceNotFoundException("Credentials for %s not found".formatted(request.getUrl()));
                    }

                    ExternalServiceCredentialsResponse response = new ExternalServiceCredentialsResponse()
                            .setHeaderName(header.getHeaderName())
                            .setHeaderValue(header.getHeaderValue());

                    if (AuthenticationType.OAUTH.equals(credentials.getAuthenticationType())
                            && credentials.getExpiresInSeconds() != null) {
                        // updatedAt is stored as Unix epoch milliseconds (TimeProvider#getCurrentTime()),
                        // expiresInSeconds is seconds. Spec mandates Unix epoch seconds.
                        response.setExpiresAt(credentials.getUpdatedAt() / 1000L + credentials.getExpiresInSeconds());
                    }
                    return response;
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

                    String[] scope = CredentialsLocatorFactory.parseExternalServiceScope(request.getUrl());
                    try {
                        ResolvedExternalService resolved = resolveExternalService(request.getUrl(), request.getOwnerSub());

                        if (!callerMatchesAppIdentity(resolved.application.getAppIdentity())) {
                            throw new PermissionDeniedException("Caller identity does not match the application's app_identity");
                        }

                        ResourceAuthSettings authSettings = resolved.externalService.getAuthSettings();
                        CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScopeForOwner(
                                request.getUrl(), request.getOwnerSub(), context);
                        ResourceCredentials credentials = resourceCredentialsService.getRefreshedUserCredentials(
                                locator, authSettings, request.getOwnerSub());

                        // Fail closed: no fallback to APP/GLOBAL. Missing owner credential ⇒ 404.
                        if (credentials == null) {
                            throw new ResourceNotFoundException("Credentials for %s not found".formatted(request.getUrl()));
                        }
                        if (!credentials.isOfflineUsageConsent()) {
                            throw new PermissionDeniedException("Offline usage consent required for on-behalf-of retrieval");
                        }

                        AuthorizationHeader header = authorizationHeaderProvider.createAuthorizationHeader(credentials);
                        if (header == null) {
                            throw new ResourceNotFoundException("Credentials for %s not found".formatted(request.getUrl()));
                        }

                        ExternalServiceCredentialsResponse response = new ExternalServiceCredentialsResponse()
                                .setHeaderName(header.getHeaderName())
                                .setHeaderValue(header.getHeaderValue());

                        if (AuthenticationType.OAUTH.equals(credentials.getAuthenticationType())
                                && credentials.getExpiresInSeconds() != null) {
                            response.setExpiresAt(credentials.getUpdatedAt() / 1000L + credentials.getExpiresInSeconds());
                        }
                        ExternalServiceAuditLog.oboRetrieval(context, scope[0], scope[1], request.getOwnerSub(), null);
                        return response;
                    } catch (RuntimeException e) {
                        ExternalServiceAuditLog.oboRetrieval(context, scope[0], scope[1], request.getOwnerSub(), e);
                        throw e;
                    }
                }))
                .onSuccess(response -> context.respond(HttpStatus.OK, response))
                .onFailure(error -> respondError("Can't get on-behalf-of external service credentials", error));

        return Future.succeededFuture();
    }

    // Derive the caller's identity by how it authenticated and compare to the app's app_identity:
    // DIAL-key form = SHA-256 of the presented key; workload form = the JWT's azp (Azure v1: appid).
    private boolean callerMatchesAppIdentity(String appIdentity) {
        if (appIdentity == null) {
            return false;
        }
        Key key = context.getKey();
        if (key != null && key.getKey() != null && appIdentity.equals(sha256Hex(key.getKey()))) {
            return true;
        }
        String azp = callerAuthorizedParty();
        return azp != null && appIdentity.equals(azp);
    }

    private String callerAuthorizedParty() {
        ExtractedClaims claims = context.getExtractedClaims();
        if (claims == null || claims.userClaims() == null) {
            return null;
        }
        JsonNode azp = claims.userClaims().get("azp");
        if (azp == null || azp.isNull()) {
            azp = claims.userClaims().get("appid");
        }
        return azp == null || azp.isNull() ? null : azp.asText();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Canonical external-service definition resolver. Resolves the admin/inline definition first; when the
     * app permits it ({@code allow_user_external_services}) and no inline definition exists, falls back to a
     * user-authored definition in the owner's bucket (design §9/§10). {@code ownerSub} is the credential
     * owner on the OBO path; {@code null} on caller-scoped paths, where the caller is the owner.
     */
    private ResolvedExternalService resolveExternalService(String scopeId, @Nullable String ownerSub) {
        String[] parts = CredentialsLocatorFactory.parseExternalServiceScope(scopeId);
        String appPart = parts[0];
        String externalServiceId = parts[1];

        Application application;
        ResourceDescriptor appDescriptor = null;
        boolean staticApp;

        Deployment deployment = context.getConfig().selectDeployment(appPart);
        if (deployment instanceof Application configApp) {
            application = configApp;
            staticApp = true;
        } else {
            staticApp = false;
            try {
                appDescriptor = ResourceDescriptorFactory.fromAnyUrl(
                        "applications/" + UrlUtil.encodePath(appPart), encryptionService);
            } catch (IllegalArgumentException e) {
                throw new ResourceNotFoundException("Application not found: " + appPart);
            }
            application = applicationService.getApplicationWithDecryptedSecrets(appDescriptor).getValue();
            if (application == null) {
                throw new ResourceNotFoundException("Application not found: " + appPart);
            }
        }

        ExternalService externalService = application.getExternalServices() == null
                ? null : application.getExternalServices().get(externalServiceId);
        if (externalService == null && application.isAllowUserExternalServices()) {
            String owner = ownerSub != null ? ownerSub : context.getUserId();
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
        return new ResolvedExternalService(application, externalService, appDescriptor, staticApp);
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

    private record ResolvedExternalService(Application application,
                                           ExternalService externalService,
                                           ResourceDescriptor applicationDescriptor,
                                           boolean staticApp) {
    }
}
