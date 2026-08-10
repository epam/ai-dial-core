package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
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
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;

/**
 * The user's own offline credentials: one refresh token per user, platform-wide, obtained through an ordinary
 * authorization-code flow with {@code offline_access} and never handed to any application.
 *
 * <p>Deliberately not on the external-service credential endpoints. Those are app-scoped and reach their blobs
 * through {@code parseExternalServiceScope}, which requires an {@code applications/…/external_services/…} shape;
 * these credentials are stored outside it, so no application can address them.
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

    /**
     * Status and, when there is something to do, the parameters chat needs to build the authorization URL. One
     * call because they are the same question asked at the same moment.
     */
    public Future<?> getStatus() {
        if (rejectPerRequestKey()) {
            return Future.succeededFuture();
        }
        taskExecutor.submit(() -> {
            ResourceAuthSettings offlineClient = resolveOfflineClient();
            boolean connected = resourceCredentialsService.getResourceCredentials(descriptor()) != null;
            return OfflineCredentialsStatus.of(connected, connected ? null : offlineClient);
        })
                .onSuccess(status -> context.respond(HttpStatus.OK, status))
                .onFailure(error -> respondError("Can't read offline credentials", error));
        return Future.succeededFuture();
    }

    /**
     * Exchanges the authorization code and stores the resulting refresh token in the caller's own bucket.
     */
    public Future<?> signIn() {
        if (rejectPerRequestKey()) {
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

                        try {
                            resourceCredentialsService.addResourceCredentials(
                                    descriptor(), offlineClient, signIn, context.getUserId(), credentials ->
                                            verifyIssuedForCaller(provider, credentials));
                        } catch (RuntimeException e) {
                            ExternalServiceAuditLog.offlineCredentials(context, "SIGN_IN", e);
                            throw e;
                        }
                        ExternalServiceAuditLog.offlineCredentials(context, "SIGN_IN", null);
                        return true;
                    });
                })
                .onSuccess(added -> context.respond(HttpStatus.OK, added))
                .onFailure(error -> respondError("Can't sign in for offline credentials", error));
        return Future.succeededFuture();
    }

    /** Deletes the credentials. Every scheduled run for this user stops, in every application. */
    public Future<?> signOut() {
        if (rejectPerRequestKey()) {
            return Future.succeededFuture();
        }
        taskExecutor.submit(() -> {
            try {
                boolean deleted = resourceCredentialsService.deleteCredentialsRecord(descriptor());
                ExternalServiceAuditLog.offlineCredentials(context, "SIGN_OUT", null);
                return deleted;
            } catch (RuntimeException e) {
                ExternalServiceAuditLog.offlineCredentials(context, "SIGN_OUT", e);
                throw e;
            }
        })
                .onSuccess(deleted -> context.respond(HttpStatus.OK, deleted))
                .onFailure(error -> respondError("Can't sign out of offline credentials", error));
        return Future.succeededFuture();
    }

    /**
     * The exchange must have returned a token for the caller, and nobody else. With PKCE deferred nothing else
     * refuses code injection, so this fails closed when the provider returned no ID token rather than skipping.
     */
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
        // Refresh happens when the user is absent, so the provider must be recoverable from the record alone.
        credentials.setIssuer(provider.extractIssuerFromIdToken(idToken));
    }

    private IdentityProvider provider() {
        return proxy.getTokenValidator().resolveProvider(context.getRequest().getHeader(HttpHeaders.AUTHORIZATION));
    }

    private ResourceAuthSettings resolveOfflineClient() {
        return provider().getOfflineClient();
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

    /** Only a real user may manage their own offline credentials — never an application acting for them. */
    private boolean rejectPerRequestKey() {
        if (context.getApiKeyData().getPerRequestKey() != null) {
            context.respond(HttpStatus.UNAUTHORIZED, "Offline credentials cannot be managed with a per-request key");
            return true;
        }
        return false;
    }

    private void respondError(String message, Throwable error) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String body = message;
        if (error instanceof HttpException e) {
            status = e.getStatus();
            body = e.getMessage();
        } else if (error instanceof IllegalArgumentException || error instanceof NullPointerException) {
            status = HttpStatus.BAD_REQUEST;
            body = error.getMessage();
        }
        log.warn("{}: {}", message, error.getMessage());
        context.respond(status, body);
    }
}
