package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.OfflineCredentialsSignInRequest;
import com.epam.aidial.core.server.data.OfflineCredentialsStatus;
import com.epam.aidial.core.server.log.ExternalServiceAuditLog;
import com.epam.aidial.core.server.security.IdentityProvider;
import com.epam.aidial.core.server.util.CredentialsDescriptorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.validation.ValidationUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;

/**
 * The user's own offline credentials: one refresh token per user, platform-wide, never handed to any application.
 *
 * <p>Deliberately not on the app-scoped external-service endpoints: those reach their blobs through
 * {@code parseExternalServiceScope}, whose required shape these credentials sit outside.
 */
@Slf4j
public class OfflineCredentialsController {

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final ResourceCredentialsService resourceCredentialsService;
    private final Proxy proxy;

    public OfflineCredentialsController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
    }

    /** Status plus, when not connected, the parameters chat needs to build the authorization URL. */
    @ApiOperation(
            method = "GET",
            path = "/v1/user/offline-credentials",
            operationId = "getOfflineCredentials",
            tags = {"External Services"},
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = OfflineCredentialsStatus.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> getStatus() {
        if (rejectNonUserCaller()) {
            return Future.succeededFuture();
        }
        taskExecutor.submit(() -> {
            // Same test the app listing uses, so the two endpoints cannot disagree about one user.
            boolean connected = proxy.getResourceAuthSettingsService().hasUnexpiredCredentials(descriptor());
            // Resolved only when not connected: provider resolution can throw, and a connected caller does
            // not need it.
            return OfflineCredentialsStatus.of(connected, connected ? null : provider().getOfflineClient());
        })
                .onSuccess(status -> context.respond(HttpStatus.OK, status))
                .onFailure(error -> respondError("Can't read offline credentials", error));
        return Future.succeededFuture();
    }

    /** Exchanges the authorization code and stores the refresh token in the caller's own bucket. */
    @ApiOperation(
            method = "POST",
            path = "/v1/user/offline-credentials/signin",
            operationId = "offlineCredentialsSignIn",
            requestBody = @ApiSchema(implementation = OfflineCredentialsSignInRequest.class),
            tags = {"External Services"},
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = Boolean.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> signIn() {
        if (rejectNonUserCaller()) {
            return Future.succeededFuture();
        }
        context.getRequest()
                .body()
                .compose(body -> {
                    OfflineCredentialsSignInRequest request =
                            ProxyUtil.convertToObject(body, OfflineCredentialsSignInRequest.class);
                    ValidationUtil.validate(request);
                    return taskExecutor.submit(() -> {
                        IdentityProvider provider = provider();
                        ResourceAuthSettings offlineClient = requireOfflineClient(provider);

                        ResourceSignInRequest signIn = ResourceSignInRequest.builder()
                                .url(CredentialsDescriptorFactory.OFFLINE_CREDENTIALS_ID)
                                .credentialsLevel(CredentialsLevel.USER)
                                .authenticationType(AuthenticationType.OAUTH)
                                .code(request.getCode())
                                .redirectUri(request.getRedirectUri())
                                .offlineUsageConsent(true)
                                .build();

                        resourceCredentialsService.addResourceCredentials(
                                descriptor(), offlineClient, signIn, context.getUserId(), credentials ->
                                        verifyIssuedForCaller(provider, credentials));
                        return true;
                    });
                })
                .onComplete(audited("SIGN_IN"))
                .onSuccess(added -> context.respond(HttpStatus.OK, added))
                .onFailure(error -> respondError("Can't sign in for offline credentials", error));
        return Future.succeededFuture();
    }

    /** Deletes the credentials — every scheduled run for this user stops, in every application. */
    @ApiOperation(
            method = "POST",
            path = "/v1/user/offline-credentials/signout",
            operationId = "offlineCredentialsSignOut",
            tags = {"External Services"},
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = Boolean.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> signOut() {
        if (rejectNonUserCaller()) {
            return Future.succeededFuture();
        }
        taskExecutor.submit(() -> resourceCredentialsService.deleteCredentialsRecord(descriptor()))
                .onComplete(audited("SIGN_OUT"))
                .onSuccess(deleted -> context.respond(HttpStatus.OK, deleted))
                .onFailure(error -> respondError("Can't sign out of offline credentials", error));
        return Future.succeededFuture();
    }

    /** With PKCE deferred this is the only check refusing code injection, so it fails closed without an ID token. */
    private void verifyIssuedForCaller(IdentityProvider provider, ResourceCredentials credentials) {
        String idToken = credentials.getIdToken();
        if (idToken == null) {
            throw new IllegalArgumentException(
                    "The identity provider returned no ID token, so the credentials cannot be attributed to the caller");
        }
        String tokenUserId = provider.extractUserIdFromIdToken(idToken);
        if (tokenUserId == null || !tokenUserId.equals(context.getUserId())) {
            throw new HttpException(HttpStatus.FORBIDDEN,
                    "The authorization code belongs to a different user than the caller");
        }
        // Refresh runs with the user absent, so the provider must be recoverable from the record alone.
        credentials.setIssuer(provider.extractIssuerFromIdToken(idToken));
    }

    /** Audits the whole operation: a body rejected before storage is still a failed attempt. */
    private <T> Handler<AsyncResult<T>> audited(String action) {
        return result -> ExternalServiceAuditLog.offlineCredentials(context, action, asRuntime(result.cause()));
    }

    private static RuntimeException asRuntime(Throwable error) {
        if (error == null) {
            return null;
        }
        return error instanceof RuntimeException e ? e : new IllegalStateException(error);
    }

    private IdentityProvider provider() {
        return proxy.getTokenValidator().resolveProvider(context.getRequest().getHeader(HttpHeaders.AUTHORIZATION));
    }

    private ResourceAuthSettings requireOfflineClient(IdentityProvider provider) {
        ResourceAuthSettings offlineClient = provider.getOfflineClient();
        if (offlineClient == null) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Offline credentials are not configured for this identity provider");
        }
        return offlineClient;
    }

    private CredentialsDescriptor descriptor() {
        return CredentialsDescriptorFactory.offlineCredentials(context);
    }

    /** These credentials belong to a person, so the caller must be one — not an app, not a userless key. */
    private boolean rejectNonUserCaller() {
        if (context.getApiKeyData().getPerRequestKey() != null) {
            context.respond(HttpStatus.UNAUTHORIZED, "Offline credentials cannot be managed with a per-request key");
            return true;
        }
        if (context.getUserId() == null) {
            context.respond(HttpStatus.UNAUTHORIZED, "Offline credentials can only be managed by a signed-in user");
            return true;
        }
        return false;
    }

    private void respondError(String message, Throwable error) {
        ExternalServiceErrors.respond(context, message, error);
    }
}
