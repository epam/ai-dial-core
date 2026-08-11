package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignOutRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import com.epam.aidial.core.credentials.factory.ResourceCredentialsFactory;
import com.epam.aidial.core.credentials.factory.ResourceCredentialsFactoryProvider;
import com.epam.aidial.core.credentials.service.token.TokenRefreshStrategyFactory;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.credentials.util.TimeProvider;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class ResourceCredentialsService {

    private final ResourceService resourceService;
    private final CredentialEncryptionService encryptionService;
    private final ResourceCredentialsFactoryProvider resourceCredentialsFactoryProvider;
    private final TokenService tokenService;
    private final TokenRefreshStrategyFactory tokenRefreshStrategyFactory;
    private final TimeProvider timeProvider;

    public void addResourceCredentials(CredentialsDescriptor credentialsDescriptor,
                                       ResourceAuthSettings resourceAuthSettings,
                                       ResourceSignInRequest resourceSignInRequest,
                                       String userId) {
        addResourceCredentials(credentialsDescriptor, resourceAuthSettings, resourceSignInRequest, userId, credentials -> { });
    }

    /**
     * As above, but runs {@code verifier} on the issued credentials <b>before</b> anything is stored, so a caller
     * can refuse them. The ID token is verification material and is cleared rather than persisted.
     */
    public void addResourceCredentials(CredentialsDescriptor credentialsDescriptor,
                                       ResourceAuthSettings resourceAuthSettings,
                                       ResourceSignInRequest resourceSignInRequest,
                                       String userId,
                                       Consumer<ResourceCredentials> verifier) {
        log.info("Adding resource credentials for resourceId={}, bucket={}, credentialsLevel={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName(), resourceSignInRequest.getCredentialsLevel());
        ResourceCredentialsFactory factory = resourceCredentialsFactoryProvider.getFactory(resourceSignInRequest.getAuthenticationType());
        ResourceCredentials resourceCredentials = factory.createCredentials(resourceSignInRequest.getUrl(), resourceAuthSettings, resourceSignInRequest);

        if (resourceSignInRequest.getCredentialsLevel().equals(CredentialsLevel.USER)) {
            resourceCredentials.setUserId(userId);
            // Offline-usage consent is per-user; record it so the on-behalf-of retrieval path can gate on it.
            resourceCredentials.setOfflineUsageConsent(resourceSignInRequest.isOfflineUsageConsent());
        }

        verifier.accept(resourceCredentials);
        resourceCredentials.setIdToken(null);

        byte[] encryptedBody = encrypt(credentialsDescriptor, resourceCredentials);
        resourceService.putResourceBytes(credentialsDescriptor.toResourceDescriptor(), encryptedBody, EtagHeader.ANY);
        log.info("Resource credentials for resourceId={}, bucket={} stored successfully",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
    }

    public boolean copyResourceCredentials(CredentialsDescriptor from, CredentialsDescriptor to,
                                           CredentialsLevel credentialsLevel, boolean overwrite) {
        log.info("Copying resource credentials for resourceId={} from bucket={} to bucket={}",
                from.getResourceId(), from.getBucketName(), to.getBucketName());

        ResourceCredentials resourceCredentials = getResourceCredentials(from);
        if (resourceCredentials == null) {
            log.debug("No resource credentials found for resourceId={} from bucket={}",
                    from.getResourceId(), from.getBucketName());
            return false;
        }
        if (!resourceCredentials.getCredentialsLevel().equals(credentialsLevel)) {
            log.debug("Resource credentials found for resourceId={} from bucket={} has level={}, required level={}",
                    from.getResourceId(), from.getBucketName(), resourceCredentials.getCredentialsLevel(), credentialsLevel);
            return false;
        }

        resourceCredentials.setResourceId(to.getResourceId());

        byte[] encryptedBody = encrypt(to, resourceCredentials);
        EtagHeader etag = overwrite ? EtagHeader.ANY : EtagHeader.NEW_ONLY;
        resourceService.putResourceBytes(to.toResourceDescriptor(), encryptedBody, etag);
        log.info("Resource credentials for resourceId={} copied successfully from bucket={} to bucket={}",
                from.getResourceId(), from.getBucketName(), to.getBucketName());
        return true;
    }

    public List<ResourceCredentials> getAllResourceCredentials(CredentialsLocator credentialsLocator) {
        log.debug("Fetching all resource credentials for resourceId={}", credentialsLocator.getResourceId());
        try {
            return credentialsLocator.getUniqueCredentialsDescriptors().stream()
                    .map(this::getResourceCredentials)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (EncryptionException encryptionException) {
            log.warn("Encryption error. No valid Resource Credentials found.");
            return new ArrayList<>();
        }
    }

    @Nullable
    public ResourceCredentials getResourceCredentials(CredentialsDescriptor credentialsDescriptor) {
        log.debug("Fetching resource credentials for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
        byte[] encryptedBody = resourceService.getResourceBytes(credentialsDescriptor.toResourceDescriptor());
        if (encryptedBody != null) {
            ResourceCredentials resourceCredentials = decrypt(credentialsDescriptor, encryptedBody);
            log.debug("Successfully decrypted credentials for resourceId={}, bucket={}",
                    credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
            return resourceCredentials;
        }
        log.debug("No credentials found for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
        return null;
    }

    public boolean deleteResourceCredentials(CredentialsLocator credentialsLocator,
                                             ResourceSignOutRequest resourceSignOutRequest,
                                             String userSub) {
        log.info("Deleting resource credentials for resourceId={}.", credentialsLocator.getResourceId());
        CredentialsLevel signOutRequestCredentialsLevel = resourceSignOutRequest.getCredentialsLevel();
        CredentialsDescriptor credentialsDescriptor = credentialsLocator.getCredentialsDescriptors().get(signOutRequestCredentialsLevel);
        MutableObject<Boolean> result = new MutableObject<>(false);

        resourceService.computeResourceBytes(credentialsDescriptor.toResourceDescriptor(), existingCredentialsBytesEncrypted -> {
            if (existingCredentialsBytesEncrypted == null) {
                throw new ResourceNotFoundException("Credentials for %s not found".formatted(credentialsDescriptor.getResourceId()));
            }

            ResourceCredentials existingCredentials = decrypt(credentialsDescriptor, existingCredentialsBytesEncrypted);
            validateDeleteOperation(existingCredentials, signOutRequestCredentialsLevel, userSub);
            result.setValue(true);
            return null;
        });

        log.info("Deleting resource credentials for resourceId={}, bucket={} finished. Result: {}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName(), result.get());
        return result.get();
    }

    public void deleteResourceCredentials(CredentialsLocator credentialsLocator) {
        log.info("Deleting all resource credentials for resourceId={}.", credentialsLocator.getResourceId());

        credentialsLocator.getUniqueCredentialsDescriptors().forEach(credentialsDescriptor -> {
            resourceService.deleteResource(credentialsDescriptor.toResourceDescriptor(), EtagHeader.ANY);
            log.debug("Deleting resource credentials for resourceId={}, bucket={}",
                    credentialsLocator.getResourceId(), credentialsDescriptor.getBucketName());
        });

        log.info("Deleting all resource credentials for resourceId={} finished", credentialsLocator.getResourceId());
    }

    /**
     * Idempotently deletes credentials at a single level (used to cascade APP-level credentials when
     * an external-service definition is removed). No-op when none exist.
     */
    public void deleteResourceCredentialsAtLevel(CredentialsLocator credentialsLocator, CredentialsLevel credentialsLevel) {
        CredentialsDescriptor descriptor = credentialsLocator.getCredentialsDescriptors().get(credentialsLevel);
        if (descriptor == null) {
            return;
        }
        resourceService.deleteResource(descriptor.toResourceDescriptor(), EtagHeader.ANY);
        log.debug("Deleted {}-level credentials for resourceId={}, bucket={}",
                credentialsLevel, credentialsLocator.getResourceId(), descriptor.getBucketName());
    }

    private void validateDeleteOperation(ResourceCredentials existingCredentials,
                                         CredentialsLevel credentialsLevel,
                                         String userId) {
        CredentialsLevel existingResourceCredentialsLevel = existingCredentials.getCredentialsLevel();
        Objects.requireNonNull(existingResourceCredentialsLevel, "Invalid saved credentials: missing CredentialsLevel");

        if (!existingResourceCredentialsLevel.equals(credentialsLevel)) {
            throw new IllegalArgumentException("Invalid CredentialsLevel: %s in resource sign out request".formatted(credentialsLevel));
        }

        if (credentialsLevel.equals(CredentialsLevel.USER)) {
            String existingCredentialsUserId = existingCredentials.getUserId();
            Objects.requireNonNull(existingCredentialsUserId, "Invalid saved credentials: missing userSub");
            if (!existingCredentialsUserId.equals(userId)) {
                throw new IllegalArgumentException("Can't delete other user's personal credentials");
            }
        }
    }

    /** Stores a prepared record directly, for records with no credential material to fetch. Stamps both times. */
    public void putCredentialsRecord(CredentialsDescriptor credentialsDescriptor, ResourceCredentials credentials) {
        log.info("Storing credentials record for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
        long now = timeProvider.getCurrentTime();
        credentials.setCreatedAt(now);
        credentials.setUpdatedAt(now);
        byte[] encryptedBody = encrypt(credentialsDescriptor, credentials);
        resourceService.putResourceBytes(credentialsDescriptor.toResourceDescriptor(), encryptedBody, EtagHeader.ANY);
    }

    /** Deletes one record addressed directly, for records that are not app-scoped. */
    public boolean deleteCredentialsRecord(CredentialsDescriptor credentialsDescriptor) {
        log.info("Deleting resource credentials for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
        return resourceService.deleteResource(credentialsDescriptor.toResourceDescriptor(), EtagHeader.ANY);
    }

    /**
     * NONE has no credential by definition; DIAL_NATIVE has none <b>per service</b> — a record left from before a
     * switch to either type must never be served, and APPLICATION-level purging cannot reach USER-level leftovers.
     */
    private static boolean hasNoServiceCredential(ResourceAuthSettings authSettings) {
        AuthenticationType type = authSettings.getAuthenticationType();
        return type == AuthenticationType.NONE || type == AuthenticationType.DIAL_NATIVE;
    }

    @Nullable
    public ResourceCredentials getRefreshedResourceCredentials(CredentialsLocator credentialsLocator,
                                                               ResourceAuthSettings authSettings,
                                                               String userSub) {
        if (hasNoServiceCredential(authSettings)) {
            return null;
        }

        Map<CredentialsLevel, CredentialsDescriptor> descriptors = credentialsLocator.getCredentialsDescriptors();

        CredentialsDescriptor userDescriptor = descriptors.get(CredentialsLevel.USER);
        if (userDescriptor != null) {
            ResourceCredentials userCredentials = getAndRefreshCredentials(userDescriptor, authSettings);
            if (userCredentials != null
                    && userCredentials.getCredentialsLevel().equals(CredentialsLevel.USER)
                    && Objects.equals(userSub, userCredentials.getUserId())) {
                return userCredentials;
            }
        }

        // GLOBAL (toolsets) then APPLICATION (external services); a locator only carries the levels it supports.
        for (CredentialsLevel level : List.of(CredentialsLevel.GLOBAL, CredentialsLevel.APPLICATION)) {
            CredentialsDescriptor descriptor = descriptors.get(level);
            if (descriptor == null) {
                continue;
            }
            ResourceCredentials credentials = getAndRefreshCredentials(descriptor, authSettings);
            if (credentials != null && level.equals(credentials.getCredentialsLevel())) {
                return credentials;
            }
        }

        return null;
    }

    /**
     * Resolves the owner's USER-level credential only, with auto-refresh — and <b>no fallback</b> to
     * GLOBAL/APPLICATION levels. Used by the on-behalf-of (OBO) retrieval path, which must fail closed
     * rather than silently serve a shared/app-level identity. Returns {@code null} when the owner has no
     * USER-level credential for the scope (the locator must carry only a USER bucket, but the owner user-id
     * match is enforced defensively here too).
     *
     * <p>When a credential exists but the owner did not grant offline-usage consent, it is returned <b>as
     * stored, un-refreshed</b> (the refresh token is deliberately not rotated) so the caller can reject on
     * consent rather than mistake it for a missing credential.
     */
    @Nullable
    public ResourceCredentials getRefreshedUserCredentials(CredentialsLocator credentialsLocator,
                                                           ResourceAuthSettings authSettings,
                                                           String ownerUserId) {
        if (hasNoServiceCredential(authSettings)) {
            return null;
        }

        CredentialsDescriptor userDescriptor = credentialsLocator.getCredentialsDescriptors().get(CredentialsLevel.USER);
        if (userDescriptor == null) {
            return null;
        }

        ResourceCredentials stored = getResourceCredentials(userDescriptor);
        if (stored == null
                || !stored.getCredentialsLevel().equals(CredentialsLevel.USER)
                || !Objects.equals(ownerUserId, stored.getUserId())) {
            return null;
        }
        // Gate consent BEFORE refreshing: an OBO probe against a non-consented credential must not rotate the
        // owner's refresh token. Return the stored (un-refreshed) credential so the caller can reject on consent.
        if (!stored.isOfflineUsageConsent()) {
            return stored;
        }

        ResourceCredentials userCredentials = getAndRefreshCredentials(userDescriptor, authSettings);
        // Re-check consent on the freshly re-read credential: if the owner revoked it concurrently with this
        // refresh, don't serve the rotated token. (The refresh itself may still have rotated once in that narrow
        // window — accepted, since revocation via sign-out normally deletes the credential outright.)
        if (userCredentials != null
                && userCredentials.getCredentialsLevel().equals(CredentialsLevel.USER)
                && Objects.equals(ownerUserId, userCredentials.getUserId())
                && userCredentials.isOfflineUsageConsent()) {
            return userCredentials;
        }

        return null;
    }

    private ResourceCredentials getAndRefreshCredentials(CredentialsDescriptor credentialsDescriptor,
                                                         ResourceAuthSettings authSettings) {
        String resourceId = credentialsDescriptor.getResourceId();
        String bucketName = credentialsDescriptor.getBucketName();
        log.debug("Updating resource credentials for resourceId={}, bucket={}", resourceId, bucketName);

        MutableObject<ResourceCredentials> reference = new MutableObject<>();
        MutableObject<Exception> refreshFailure = new MutableObject<>();
        resourceService.computeResourceBytes(credentialsDescriptor.toResourceDescriptor(), existingCredentialsBytesEncrypted -> {
            if (existingCredentialsBytesEncrypted == null) {
                log.debug("No credentials found for resourceId={}, bucket={}", resourceId, bucketName);
                return null;
            }

            ResourceCredentials resourceCredentials = decrypt(credentialsDescriptor, existingCredentialsBytesEncrypted);

            if (tokenRefreshStrategyFactory.getTokenValidatorStrategy(authSettings.getAuthenticationType()).requiresTokenRefresh(resourceCredentials)) {
                try {
                    updateExpiredResourceCredentials(resourceCredentials, resourceId, authSettings);
                } catch (HttpException e) {
                    if (isPermanentRefreshFailure(e)) {
                        log.warn("Failed to refresh token for resourceId={}, bucket={}. Removing credentials", resourceId, bucketName, e);
                        refreshFailure.setValue(e);
                        return null;
                    }
                    throw e;
                }
                reference.setValue(resourceCredentials);
                log.debug("Encrypting and storing refreshed credentials for resourceId={}, bucket={}", resourceId, bucketName);
                return encrypt(credentialsDescriptor, resourceCredentials);
            }

            log.debug("Encrypting and storing updated credentials for resourceId={}, bucket={}", resourceId, bucketName);
            reference.setValue(resourceCredentials);
            return existingCredentialsBytesEncrypted;
        });

        if (refreshFailure.get() != null) {
            // The toolset still exists; only its OAuth state is gone. 401 tells the client to re-auth
            // rather than implying the resource itself is missing.
            throw new HttpException(HttpStatus.UNAUTHORIZED, "Failed to refresh token for resource %s".formatted(resourceId));
        }

        return reference.get();
    }

    // 4xx on a token endpoint indicates a client-side problem — refresh token revoked/expired,
    // client auth rejected, or malformed request. Safe to drop stored credentials so the user
    // is prompted to sign in again. Transient issues (5xx, network) are rethrown and leave creds in place.
    private static boolean isPermanentRefreshFailure(HttpException e) {
        HttpStatus status = e.getStatus();
        return status == HttpStatus.BAD_REQUEST || status == HttpStatus.UNAUTHORIZED;
    }

    private void updateExpiredResourceCredentials(ResourceCredentials resourceCredentials,
                                                  String resourceId,
                                                  ResourceAuthSettings authSettings) {
        log.info("Start updating expired token for Resource: {}", resourceId);
        TokenResponse newAccessTokenResponse = tokenService.getToken(resourceId,
                authSettings, resourceCredentials.getRefreshToken());

        resourceCredentials.setExpiresInSeconds(newAccessTokenResponse.getExpiresIn());
        resourceCredentials.setUpdatedAt(timeProvider.getCurrentTime());
        resourceCredentials.setAccessToken(newAccessTokenResponse.getAccessToken());
        // RFC 6749 §6: no refresh token in the response leaves the existing one in force. Overwriting it with
        // null would end offline access on providers that do not rotate.
        if (newAccessTokenResponse.getRefreshToken() != null) {
            resourceCredentials.setRefreshToken(newAccessTokenResponse.getRefreshToken());
        }
        log.debug("Finished updating expired token for Resource: {}", resourceId);
    }

    private byte[] encrypt(CredentialsDescriptor credentialsDescriptor, ResourceCredentials resourceCredentials) {
        log.trace("Encrypting credentials for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
        BucketInfo bucketInfo = new BucketInfo(credentialsDescriptor.getBucketName(), credentialsDescriptor.getBucketLocation());
        byte[] aad = credentialsDescriptor.getFullPath().getBytes(StandardCharsets.UTF_8);
        byte[] data = JsonMapperUtil.convertToString(resourceCredentials).getBytes(StandardCharsets.UTF_8);
        return encryptionService.encrypt(bucketInfo, data, aad);
    }

    private ResourceCredentials decrypt(CredentialsDescriptor credentialsDescriptor, byte[] encryptedData) {
        log.trace("Decrypting credentials for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
        BucketInfo bucketInfo = new BucketInfo(credentialsDescriptor.getBucketName(), credentialsDescriptor.getBucketLocation());
        byte[] aad = credentialsDescriptor.getFullPath().getBytes(StandardCharsets.UTF_8);
        byte[] data = encryptionService.decrypt(bucketInfo, encryptedData, aad);
        return JsonMapperUtil.convertToObject(data, ResourceCredentials.class);
    }
}