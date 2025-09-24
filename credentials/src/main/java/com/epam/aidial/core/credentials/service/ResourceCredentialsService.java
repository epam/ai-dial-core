package com.epam.aidial.core.credentials.service;

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
import com.epam.aidial.core.credentials.factory.ResourceCredentialsFactory;
import com.epam.aidial.core.credentials.factory.ResourceCredentialsFactoryProvider;
import com.epam.aidial.core.credentials.service.token.TokenRefreshStrategyFactory;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.credentials.util.TimeProvider;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

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
                                       String userSub) {
        log.debug("Adding resource credentials for resourceId={}, bucket={}, credentialsLevel={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName(), resourceSignInRequest.getCredentialsLevel());
        ResourceCredentialsFactory factory = resourceCredentialsFactoryProvider.getFactory(resourceSignInRequest.getAuthenticationType());
        ResourceCredentials resourceCredentials = factory.createCredentials(resourceSignInRequest.getUrl(), resourceAuthSettings, resourceSignInRequest);

        if (resourceSignInRequest.getCredentialsLevel().equals(CredentialsLevel.USER)) {
            resourceCredentials.setUserSub(userSub);
        }

        byte[] encryptedBody = encrypt(credentialsDescriptor, resourceCredentials);
        resourceService.putResourceBytes(credentialsDescriptor.toResourceDescriptor(), encryptedBody, EtagHeader.ANY);
        log.debug("Resource credentials for resourceId={}, bucket={} stored successfully",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
    }

    public boolean copyResourceCredentials(CredentialsDescriptor from, CredentialsDescriptor to, CredentialsLevel credentialsLevel) {
        log.debug("Copying resource credentials for resourceId={} from bucket={} to bucket={}",
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

        byte[] encryptedBody = encrypt(to, resourceCredentials);
        resourceService.putResourceBytes(to.toResourceDescriptor(), encryptedBody, EtagHeader.ANY);
        log.debug("Resource credentials for resourceId={} copied successfully from bucket={} to bucket={}",
                from.getResourceId(), from.getBucketName(), to.getBucketName());
        return true;
    }

    public List<ResourceCredentials> getAllResourceCredentials(CredentialsLocator credentialsLocator) {
        log.debug("Fetching all resource credentials for resourceId={}", credentialsLocator.getResourceId());
        return credentialsLocator.getUniqueCredentialsDescriptors().stream()
                .map(this::getResourceCredentials)
                .filter(Objects::nonNull)
                .toList();
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
        log.debug("Deleting resource credentials for resourceId={}.", credentialsLocator.getResourceId());
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

        log.debug("Deleting resource credentials for resourceId={}, bucket={} finished. Result: {}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName(), result.get());
        return result.get();
    }

    public void deleteResourceCredentials(CredentialsLocator credentialsLocator) {
        log.debug("Deleting all resource credentials for resourceId={}.", credentialsLocator.getResourceId());

        credentialsLocator.getUniqueCredentialsDescriptors().forEach(credentialsDescriptor -> {
            resourceService.deleteResource(credentialsDescriptor.toResourceDescriptor(), EtagHeader.ANY);
            log.debug("Deleting resource credentials for resourceId={}, bucket={}",
                    credentialsLocator.getResourceId(), credentialsDescriptor.getBucketName());
        });

        log.debug("Deleting all resource credentials for resourceId={} finished", credentialsLocator.getResourceId());
    }

    private void validateDeleteOperation(ResourceCredentials existingCredentials,
                                         CredentialsLevel credentialsLevel,
                                         String userSub) {
        CredentialsLevel existingResourceCredentialsLevel = existingCredentials.getCredentialsLevel();
        Objects.requireNonNull(existingResourceCredentialsLevel, "Invalid saved credentials: missing CredentialsLevel");

        if (!existingResourceCredentialsLevel.equals(credentialsLevel)) {
            throw new IllegalArgumentException("Invalid CredentialsLevel: %s in resource sign out request".formatted(credentialsLevel));
        }

        if (credentialsLevel.equals(CredentialsLevel.USER)) {
            String existingCredentialsUserSub = existingCredentials.getUserSub();
            Objects.requireNonNull(existingCredentialsUserSub, "Invalid saved credentials: missing userSub");
            if (!existingCredentialsUserSub.equals(userSub)) {
                throw new IllegalArgumentException("Can't delete other user's personal credentials");
            }
        }
    }

    public ResourceCredentials getAndRefreshCredentials(CredentialsDescriptor credentialsDescriptor,
                                                        ResourceAuthSettings authSettings) {
        String resourceId = credentialsDescriptor.getResourceId();
        String bucketName = credentialsDescriptor.getBucketName();
        log.debug("Updating resource credentials for resourceId={}, bucket={}", resourceId, bucketName);

        MutableObject<ResourceCredentials> reference = new MutableObject<>();
        resourceService.computeResourceBytes(credentialsDescriptor.toResourceDescriptor(), existingCredentialsBytesEncrypted -> {
            if (existingCredentialsBytesEncrypted == null) {
                throw new ResourceNotFoundException("Credentials for %s not found".formatted(credentialsDescriptor.getResourceId()));
            }

            ResourceCredentials resourceCredentials = decrypt(credentialsDescriptor, existingCredentialsBytesEncrypted);

            if (tokenRefreshStrategyFactory.getTokenValidatorStrategy(authSettings.getAuthenticationType()).requiresTokenRefresh(resourceCredentials)) {
                updateExpiredResourceCredentials(resourceCredentials, resourceId, authSettings);
                reference.setValue(resourceCredentials);
                log.debug("Encrypting and storing refreshed credentials for resourceId={}, bucket={}", resourceId, bucketName);
                return encrypt(credentialsDescriptor, resourceCredentials);
            }

            log.debug("Encrypting and storing updated credentials for resourceId={}, bucket={}", resourceId, bucketName);
            reference.setValue(resourceCredentials);
            return existingCredentialsBytesEncrypted;
        });
        return reference.get();
    }

    private void updateExpiredResourceCredentials(ResourceCredentials resourceCredentials,
                                                  String resourceId,
                                                  ResourceAuthSettings authSettings) {
        log.debug("Start updating expired token for Resource: {}", resourceId);
        TokenResponse newAccessTokenResponse = tokenService.getToken(resourceId,
                authSettings, resourceCredentials.getRefreshToken());

        resourceCredentials.setExpiresInSeconds(newAccessTokenResponse.getExpiresIn());
        resourceCredentials.setUpdatedAt(timeProvider.getCurrentTime());
        resourceCredentials.setAccessToken(newAccessTokenResponse.getAccessToken());
        resourceCredentials.setRefreshToken(newAccessTokenResponse.getRefreshToken());
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