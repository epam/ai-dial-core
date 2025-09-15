package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class ResourceCredentialsService {

    private final ResourceService resourceService;
    private final CredentialEncryptionService encryptionService;

    public void addResourceCredentials(CredentialsDescriptor credentialDescriptor, ResourceCredentials resourceCredentials) {
        log.debug("Adding resource credentials for resourceId={}, bucket={}, credentialsLevel={}",
                credentialDescriptor.getResourceId(), credentialDescriptor.getBucketName(), resourceCredentials.getCredentialsLevel());
        byte[] body = JsonMapperUtil.convertToString(resourceCredentials).getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBody = encrypt(credentialDescriptor, body);
        resourceService.putResourceBytes(credentialDescriptor.toResourceDescriptor(), encryptedBody, EtagHeader.ANY);
        log.debug("Resource credentials for resourceId={}, bucket={} stored successfully",
                credentialDescriptor.getResourceId(), credentialDescriptor.getBucketName());
    }

    public List<ResourceCredentials> getAllResourceCredentials(CredentialsLocator credentialsLocator) {
        log.debug("Fetching all resource credentials for resourceId={}", credentialsLocator.getResourceId());
        return credentialsLocator.getUniqueCredentialsDescriptors().stream()
                .map(this::getResourceCredentials)
                .filter(Objects::nonNull)
                .toList();
    }

    public ResourceCredentials getResourceCredentials(CredentialsDescriptor credentialsDescriptor) {
        log.debug("Fetching resource credentials for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
        byte[] encryptedBody = resourceService.getResourceBytes(credentialsDescriptor.toResourceDescriptor());
        if (encryptedBody != null) {
            byte[] body = decrypt(credentialsDescriptor, encryptedBody);
            log.debug("Successfully decrypted credentials for resourceId={}, bucket={}",
                    credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
            return JsonMapperUtil.convertToObject(body, ResourceCredentials.class);
        }
        log.debug("No credentials found for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
        return null;
    }

    public void updateAllResourceCredentials(
            CredentialsLocator credentialsLocator,
            List<ResourceCredentials> toolSetCredentialsList) {

        log.debug("Updating all resource credentials for resourceId={}", credentialsLocator.getResourceId());

        Map<CredentialsLevel, ResourceCredentials> credentialsByLevel = toolSetCredentialsList.stream()
                .collect(Collectors.toMap(ResourceCredentials::getCredentialsLevel, c -> c));

        validateLevels(credentialsLocator, credentialsByLevel);

        Map<CredentialsDescriptor, Set<CredentialsLevel>> descriptorToLevels =
                groupByDescriptor(credentialsLocator.getCredentialsDescriptors());

        for (Map.Entry<CredentialsDescriptor, Set<CredentialsLevel>> entry : descriptorToLevels.entrySet()) {
            CredentialsDescriptor descriptor = entry.getKey();
            ResourceCredentials resolvedCredential = resolveSingleCredential(entry.getValue(), credentialsByLevel, descriptor);
            updateResourceCredentials(descriptor, resolvedCredential);
        }

        log.debug("All resource credentials updated successfully for resourceId={}", credentialsLocator.getResourceId());
    }

    private void validateLevels(CredentialsLocator locator, Map<CredentialsLevel, ResourceCredentials> credentialsByLevel) {
        Set<CredentialsLevel> requiredLevels = credentialsByLevel.keySet();
        Set<CredentialsLevel> availableLevels = locator.getBuckets().keySet();

        log.debug("Validating credential levels for resourceId={}: available={}, provided={}",
                locator.getResourceId(), availableLevels, requiredLevels);
        if (!availableLevels.containsAll(requiredLevels)) {
            throw new IllegalArgumentException(
                    "Credential levels mismatch. Available: %s, Provided: %s".formatted(availableLevels, requiredLevels)
            );
        }
    }

    private Map<CredentialsDescriptor, Set<CredentialsLevel>> groupByDescriptor(
            Map<CredentialsLevel, CredentialsDescriptor> credentialsDescriptors) {

        return credentialsDescriptors.entrySet().stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toSet())
                ));
    }

    private ResourceCredentials resolveSingleCredential(
            Set<CredentialsLevel> levels,
            Map<CredentialsLevel, ResourceCredentials> credentialsByLevel,
            CredentialsDescriptor descriptor) {

        log.debug("Resolving single credential for resourceId={}, bucket={} from {} possible levels",
                descriptor.getResourceId(), descriptor.getBucketName(), levels.size());
        List<ResourceCredentials> matchedCredentials = levels.stream()
                .map(credentialsByLevel::get)
                .filter(Objects::nonNull)
                .toList();

        if (matchedCredentials.isEmpty()) {
            log.debug("No credentials found for resourceId={}, bucket={}, levels={}",
                    descriptor.getResourceId(), descriptor.getBucketName(), levels);
            return null;
        }

        if (matchedCredentials.size() > 1) {
            throw new IllegalArgumentException(
                    "Duplicate credentials found for resource %s".formatted(descriptor.getResourceId())
            );
        }

        log.debug("Single credential resolved for resourceId={}, bucket={}, levels={}",
                descriptor.getResourceId(), descriptor.getBucketName(), levels);
        return matchedCredentials.getFirst();
    }

    public void updateResourceCredentials(CredentialsDescriptor credentialsDescriptor,
                                          ResourceCredentials credentials) {
        log.debug("Updating resource credentials for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());

        resourceService.computeResourceBytes(credentialsDescriptor.toResourceDescriptor(), existing -> {
            if (existing == null) {
                throw new ResourceNotFoundException("Credentials for %s not found"
                        .formatted(credentialsDescriptor.getResourceId()));
            }
            if (credentials == null) {
                log.debug("Null credentials provided for resourceId={}, bucket={}. Deleting existing credentials",
                        credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
                return null;
            }

            log.debug("Encrypting and storing updated credentials for resourceId={}, bucket={}",
                    credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
            byte[] body = JsonMapperUtil.convertToString(credentials).getBytes(StandardCharsets.UTF_8);
            return encrypt(credentialsDescriptor, body);
        });
    }

    private byte[] encrypt(CredentialsDescriptor credentialsDescriptor, byte[] data) {
        log.trace("Encrypting credentials for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
        BucketInfo bucketInfo = new BucketInfo(credentialsDescriptor.getBucketName(), credentialsDescriptor.getBucketLocation());
        byte[] aad = credentialsDescriptor.getFullPath().getBytes(StandardCharsets.UTF_8);
        return encryptionService.encrypt(bucketInfo, data, aad);
    }

    private byte[] decrypt(CredentialsDescriptor credentialsDescriptor, byte[] data) {
        log.trace("Decrypting credentials for resourceId={}, bucket={}",
                credentialsDescriptor.getResourceId(), credentialsDescriptor.getBucketName());
        BucketInfo bucketInfo = new BucketInfo(credentialsDescriptor.getBucketName(), credentialsDescriptor.getBucketLocation());
        byte[] aad = credentialsDescriptor.getFullPath().getBytes(StandardCharsets.UTF_8);
        return encryptionService.decrypt(bucketInfo, data, aad);
    }
}