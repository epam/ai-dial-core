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
        byte[] body = JsonMapperUtil.convertToString(resourceCredentials).getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBody = encrypt(credentialDescriptor, body);
        resourceService.putResourceBytes(credentialDescriptor.toResourceDescriptor(), encryptedBody, EtagHeader.ANY);
    }

    public List<ResourceCredentials> getAllResourceCredentials(CredentialsLocator credentialsLocator) {
        return credentialsLocator.getUniqueCredentialsDescriptors().stream()
                .map(this::getResourceCredentials)
                .filter(Objects::nonNull)
                .toList();
    }

    public ResourceCredentials getResourceCredentials(CredentialsDescriptor credentialsDescriptor) {
        byte[] encryptedBody = resourceService.getResourceBytes(credentialsDescriptor.toResourceDescriptor());
        if (encryptedBody != null) {
            byte[] body = decrypt(credentialsDescriptor, encryptedBody);
            return JsonMapperUtil.convertToObject(body, ResourceCredentials.class);
        }
        return null;
    }

    public void updateAllResourceCredentials(
            CredentialsLocator credentialsLocator,
            List<ResourceCredentials> toolSetCredentialsList) {

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
    }

    private void validateLevels(CredentialsLocator locator, Map<CredentialsLevel, ResourceCredentials> credentialsByLevel) {
        Set<CredentialsLevel> requiredLevels = credentialsByLevel.keySet();
        Set<CredentialsLevel> availableLevels = locator.getBuckets().keySet();

        if (!availableLevels.containsAll(requiredLevels)) {
            throw new IllegalArgumentException(
                    "Credential levels mismatch. Available: %s, Provided: %s"
                            .formatted(availableLevels, requiredLevels)
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

        List<ResourceCredentials> matchedCredentials = levels.stream()
                .map(credentialsByLevel::get)
                .filter(Objects::nonNull)
                .toList();

        if (matchedCredentials.isEmpty()) {
            return null;
        }

        if (matchedCredentials.size() > 1) {
            throw new IllegalArgumentException(
                    "Duplicate credentials found for resource %s".formatted(descriptor.getResourceId())
            );
        }

        return matchedCredentials.getFirst();
    }

    public void updateResourceCredentials(CredentialsDescriptor credentialsDescriptor,
                                          ResourceCredentials credentials) {

        resourceService.computeResourceBytes(credentialsDescriptor.toResourceDescriptor(), existing -> {
            if (existing == null) {
                throw new ResourceNotFoundException("Credentials for %s not found"
                        .formatted(credentialsDescriptor.getResourceId()));
            }
            if (credentials == null) {
                return null;
            }

            byte[] body = JsonMapperUtil.convertToString(credentials).getBytes(StandardCharsets.UTF_8);
            return encrypt(credentialsDescriptor, body);
        });
    }

    private byte[] encrypt(CredentialsDescriptor credentialsDescriptor, byte[] data) {
        BucketInfo bucketInfo = new BucketInfo(credentialsDescriptor.getBucketName(), credentialsDescriptor.getBucketLocation());
        byte[] aad = credentialsDescriptor.getFullPath().getBytes(StandardCharsets.UTF_8);
        return encryptionService.encrypt(bucketInfo, data, aad);

    }

    private byte[] decrypt(CredentialsDescriptor credentialsDescriptor, byte[] data) {
        BucketInfo bucketInfo = new BucketInfo(credentialsDescriptor.getBucketName(), credentialsDescriptor.getBucketLocation());
        byte[] aad = credentialsDescriptor.getFullPath().getBytes(StandardCharsets.UTF_8);
        return encryptionService.decrypt(bucketInfo, data, aad);
    }

}
