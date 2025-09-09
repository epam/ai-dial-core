package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.credentials.service.encryption.ContentEncryptionKeyService;
import com.epam.aidial.core.credentials.service.encryption.CredentialsEncryptionService;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class ResourceCredentialsService {

    private final ResourceService resourceService;

    private final ContentEncryptionKeyService contentEncryptionKeyService;
    private final CredentialsEncryptionService credentialsEncryptionService;

    public void addResourceCredentials(CredentialsDescriptor credentialDescriptor, ResourceCredentials resourceCredentials) {
        if (resourceCredentials.getCredentialsLevel() != CredentialsLevel.GLOBAL) {
            throw new NotImplementedException("Only GLOBAL credentials level is supported for now.");
        }
        byte[] body = JsonMapperUtil.convertToString(resourceCredentials).getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBody = encrypt(credentialDescriptor, body);
        resourceService.putResourceBytes(getResourceDescriptor(credentialDescriptor), encryptedBody, EtagHeader.ANY);
    }

    public List<ResourceCredentials> getAllResourceCredentials(CredentialsLocator credentialsLocator) {
        return credentialsLocator.getBuckets().stream()
                .map(bucket -> CredentialsDescriptor.builder()
                        .type(credentialsLocator.getType())
                        .sourceType(credentialsLocator.getSourceType())
                        .name(credentialsLocator.getName())
                        .parentFolders(credentialsLocator.getParentFolders())
                        .bucketName(bucket.getBucketName())
                        .bucketLocation(bucket.getBucketLocation())
                        .credentialsLevel(bucket.getCredentialsLevel())
                        .build())
                .map(this::getResourceCredentials)
                .toList();
    }

    public ResourceCredentials getResourceCredentials(CredentialsDescriptor credentialsDescriptor) {
        byte[] encryptedBody = resourceService.getResourceBytes(getResourceDescriptor(credentialsDescriptor));
        if (encryptedBody != null) {
            byte[] body = decrypt(credentialsDescriptor, encryptedBody);
            return JsonMapperUtil.convertToObject(body, ResourceCredentials.class);
        }
        return null;
    }

    public void updateAllResourceCredentials(CredentialsLocator credentialsLocator,
                                             List<ResourceCredentials> toolSetCredentialsList) {

        Map<CredentialsLevel, CredentialsDescriptor> levelToDescriptor = credentialsLocator.getBuckets().stream()
                .map(bucket -> CredentialsDescriptor.builder()
                        .type(credentialsLocator.getType())
                        .sourceType(credentialsLocator.getSourceType())
                        .name(credentialsLocator.getName())
                        .parentFolders(credentialsLocator.getParentFolders())
                        .bucketName(bucket.getBucketName())
                        .bucketLocation(bucket.getBucketLocation())
                        .credentialsLevel(bucket.getCredentialsLevel())
                        .build())
                .collect(Collectors.toMap(CredentialsDescriptor::getCredentialsLevel, c -> c));

        Map<CredentialsLevel, ResourceCredentials> levelToCredential = toolSetCredentialsList.stream()
                .collect(Collectors.toMap(ResourceCredentials::getCredentialsLevel, c -> c));

        if (!levelToDescriptor.keySet().containsAll(levelToCredential.keySet())) {
            throw new IllegalArgumentException("Credential levels mismatch. Available credentials levels: %s, Current: %s"
                    .formatted(levelToDescriptor.keySet(), levelToCredential.keySet()));
        }

        for (Map.Entry<CredentialsLevel, CredentialsDescriptor> entry : levelToDescriptor.entrySet()) {
            CredentialsLevel credentialsLevel = entry.getKey();
            CredentialsDescriptor credentialsDescriptor = entry.getValue();
            ResourceCredentials resourceCredentials = levelToCredential.get(credentialsLevel);
            updateResourceCredentials(credentialsDescriptor, resourceCredentials);
        }
    }

    public void updateResourceCredentials(CredentialsDescriptor credentialsDescriptor,
                                          ResourceCredentials credentials) {

        resourceService.computeResourceBytes(getResourceDescriptor(credentialsDescriptor), existing -> {
            if (credentials == null) {
                return null;
            }

            byte[] body = JsonMapperUtil.convertToString(credentials).getBytes(StandardCharsets.UTF_8);
            return encrypt(credentialsDescriptor, body);
        });
    }

    private byte[] encrypt(CredentialsDescriptor credentialsDescriptor, byte[] data) {
        byte[] contentEncryptionKey = contentEncryptionKeyService.getOrCreateKey(credentialsDescriptor);
        byte[] aad = credentialsDescriptor.getDecodedPath().getBytes(StandardCharsets.UTF_8);
        return credentialsEncryptionService.encrypt(data, contentEncryptionKey, aad);
    }

    private byte[] decrypt(CredentialsDescriptor credentialsDescriptor, byte[] data) {
        byte[] contentEncryptionKey = contentEncryptionKeyService.getKey(credentialsDescriptor);
        if (contentEncryptionKey == null) {
            throw new ResourceNotFoundException("Content encryption key for %s %s not found"
                    .formatted(credentialsDescriptor.getType().group(), credentialsDescriptor.getResourceId()));
        }
        byte[] aad = credentialsDescriptor.getDecodedPath().getBytes(StandardCharsets.UTF_8);
        return credentialsEncryptionService.decrypt(data, contentEncryptionKey, aad);
    }

    private ResourceDescriptor getResourceDescriptor(CredentialsDescriptor credentialsDescriptor) {
        List<String> parentFolders = new ArrayList<>();
        parentFolders.add(credentialsDescriptor.getType().group());
        parentFolders.add(credentialsDescriptor.getSourceType().getValue());
        parentFolders.addAll(credentialsDescriptor.getParentFolders());

        return new ResourceDescriptor(
                ResourceTypes.CREDENTIALS,
                credentialsDescriptor.getName(),
                parentFolders,
                credentialsDescriptor.getBucketName(),
                credentialsDescriptor.getBucketLocation(),
                false
        );
    }

}
