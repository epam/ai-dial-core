package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.credentials.service.encryption.ContentEncryptionKeyService;
import com.epam.aidial.core.credentials.service.encryption.CredentialsEncryptionService;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.credentials.util.ResourceDescriptorUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ResourceCredentialsService {

    private static final String CREDENTIALS_PATH = "credentials";

    private final ResourceService resourceService;

    private final ContentEncryptionKeyService contentEncryptionKeyService;
    private final CredentialsEncryptionService credentialsEncryptionService;

    public void addResourceCredentials(ResourceDescriptor resourceDescriptor, ResourceCredentials resourceCredentials) {
        if (resourceCredentials.getCredentialsLevel() != CredentialsLevel.GLOBAL) {
            throw new NotImplementedException("Only GLOBAL credentials level is supported for now.");
        }
        ResourceDescriptor credentialsDescriptor = getCredentialsDescriptor(resourceDescriptor);
        byte[] body = JsonMapperUtil.convertToString(resourceCredentials).getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBody = encrypt(resourceDescriptor, body);
        resourceService.putResourceBytes(credentialsDescriptor, encryptedBody, EtagHeader.ANY);
    }

    public List<ResourceCredentials> getAllResourceCredentials(ResourceDescriptor resourceDescriptor) {
        ResourceDescriptor credentialsDescriptor = getCredentialsDescriptor(resourceDescriptor);
        byte[] encryptedBody = resourceService.getResourceBytes(credentialsDescriptor);
        if (encryptedBody != null) {
            byte[] body = decrypt(resourceDescriptor, encryptedBody);
            ResourceCredentials toolSetCredentials = JsonMapperUtil.convertToObject(body, ResourceCredentials.class);
            return List.of(toolSetCredentials);
        }
        return Collections.emptyList();
    }

    public void updateResourceCredentials(ResourceDescriptor resourceDescriptor,
                                          List<ResourceCredentials> toolSetCredentialsList) {
        if (toolSetCredentialsList.size() > 1) {
            throw new UnsupportedOperationException("Only a single credentials entry is supported.");
        }

        ResourceCredentials credentials = toolSetCredentialsList.isEmpty() ? null : toolSetCredentialsList.getFirst();

        if (credentials != null && credentials.getCredentialsLevel() != CredentialsLevel.GLOBAL) {
            throw new UnsupportedOperationException("Only GLOBAL credentials level is supported.");
        }

        ResourceDescriptor credentialsDescriptor = getCredentialsDescriptor(resourceDescriptor);

        resourceService.computeResourceBytes(credentialsDescriptor, existing -> {
            if (existing == null) {
                throw new ResourceNotFoundException("Credentials for ToolSet %s not found"
                        .formatted(resourceDescriptor.getDecodedUrl()));
            }
            if (credentials == null) {
                return null;
            }

            byte[] body = JsonMapperUtil.convertToString(credentials).getBytes(StandardCharsets.UTF_8);
            return encrypt(resourceDescriptor, body);
        });
    }

    private byte[] encrypt(ResourceDescriptor resourceDescriptor, byte[] data) {
        byte[] contentEncryptionKey = contentEncryptionKeyService.getOrCreateKey(resourceDescriptor);
        byte[] aad = ResourceDescriptorUtil.getDecryptedUrl(resourceDescriptor).getBytes(StandardCharsets.UTF_8);
        return credentialsEncryptionService.encrypt(data, contentEncryptionKey, aad);
    }

    private byte[] decrypt(ResourceDescriptor resourceDescriptor, byte[] data) {
        byte[] contentEncryptionKey = contentEncryptionKeyService.getKey(resourceDescriptor);
        if (contentEncryptionKey == null) {
            throw new ResourceNotFoundException("Content encryption key for ToolSet %s not found"
                    .formatted(resourceDescriptor.getDecodedUrl()));
        }
        byte[] aad = ResourceDescriptorUtil.getDecryptedUrl(resourceDescriptor).getBytes(StandardCharsets.UTF_8);
        return credentialsEncryptionService.decrypt(data, contentEncryptionKey, aad);
    }

    private ResourceDescriptor getCredentialsDescriptor(ResourceDescriptor resourceDescriptor) {
        List<String> parentFolders = new ArrayList<>();
        parentFolders.add(CREDENTIALS_PATH);
        parentFolders.addAll(resourceDescriptor.getParentFolders());

        return new ResourceDescriptor(
                ResourceTypes.CREDENTIALS,
                resourceDescriptor.getName(),
                parentFolders,
                resourceDescriptor.getBucketName(),
                resourceDescriptor.getBucketLocation(),
                false
        );
    }

}
