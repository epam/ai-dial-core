package com.epam.aidial.core.server.service.credentials;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.service.credentials.encryption.ContentEncryptionKeyService;
import com.epam.aidial.core.server.service.credentials.encryption.ToolsetCredentialsEncryptionService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
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
public class ToolSetCredentialsService {

    private final static String CREDENTIALS_PATH = "credentials";

    private final ResourceService resourceService;
    private final EncryptionService encryptionService;

    private final ContentEncryptionKeyService contentEncryptionKeyService;
    private final ToolsetCredentialsEncryptionService toolsetCredentialsEncryptionService;

    public void addToolSetCredentials(ToolSetCredentials toolSetCredentials) {
        if (toolSetCredentials.getCredentialsLevel() != CredentialsLevel.GLOBAL) {
            throw new NotImplementedException("Only GLOBAL credentials level is supported for now.");
        }
        ResourceDescriptor resourceDescriptor = getCredentialsDescriptor(toolSetCredentials.getToolSetName());
        byte[] body = ProxyUtil.convertToString(toolSetCredentials).getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBody = encrypt(toolSetCredentials.getToolSetName(), body);
        resourceService.putResourceBytes(resourceDescriptor, encryptedBody, EtagHeader.ANY);
    }

    public List<ToolSetCredentials> getAllToolSetCredentials(String toolSetName) {
        ResourceDescriptor resourceDescriptor = getCredentialsDescriptor(toolSetName);
        byte[] encryptedBody = resourceService.getResourceBytes(resourceDescriptor);
        if (encryptedBody != null) {
            byte[] body = decrypt(toolSetName, encryptedBody);
            ToolSetCredentials toolSetCredentials = ProxyUtil.convertToObject(body, ToolSetCredentials.class);
            return List.of(toolSetCredentials);
        }
        return Collections.emptyList();
    }

    public void updateToolSetCredentials(String toolSetName,
                                         List<ToolSetCredentials> toolSetCredentialsList) {
        if (toolSetCredentialsList.size() > 1) {
            throw new UnsupportedOperationException("Only a single credentials entry is supported.");
        }

        ToolSetCredentials credentials = toolSetCredentialsList.isEmpty() ? null : toolSetCredentialsList.getFirst();

        if (credentials != null && credentials.getCredentialsLevel() != CredentialsLevel.GLOBAL) {
            throw new UnsupportedOperationException("Only GLOBAL credentials level is supported.");
        }

        ResourceDescriptor descriptor = getCredentialsDescriptor(toolSetName);

        resourceService.computeResource(descriptor, existing -> {
            if (existing == null) {
                throw new ResourceNotFoundException("Credentials for ToolSet %s not found".formatted(toolSetName));
            }
            if (credentials == null) {
                return null;
            }

            byte[] body = ProxyUtil.convertToString(credentials).getBytes(StandardCharsets.UTF_8);
            byte[] encryptedBody = encrypt(toolSetName, body);
            return new String(encryptedBody, StandardCharsets.UTF_8);
        });
    }

    private byte[] encrypt(String toolSetName, byte[] data) {
        byte[] contentEncryptionKey = contentEncryptionKeyService.getOrCreateContentEncryptionKey(toolSetName);
        byte[] aad = toolSetName.getBytes(StandardCharsets.UTF_8);
        return toolsetCredentialsEncryptionService.encrypt(data, contentEncryptionKey, aad);
    }

    private byte[] decrypt(String toolSetName, byte[] data) {
        byte[] contentEncryptionKey = contentEncryptionKeyService.getContentEncryptionKey(toolSetName);
        if (contentEncryptionKey == null) {
            throw new ResourceNotFoundException("Content encryption key for ToolSet %s not found".formatted(toolSetName));
        }
        byte[] aad = toolSetName.getBytes(StandardCharsets.UTF_8);
        return toolsetCredentialsEncryptionService.decrypt(data, contentEncryptionKey, aad);
    }

    private ResourceDescriptor getCredentialsDescriptor(String toolSetName) {
        ResourceDescriptor toolsetDescriptor = ResourceDescriptorFactory.fromAnyUrl(toolSetName, encryptionService);

        List<String> parentFolders = new ArrayList<>();
        parentFolders.add(CREDENTIALS_PATH);
        parentFolders.addAll(toolsetDescriptor.getParentFolders());

        return new ResourceDescriptor(
                ResourceTypes.TOOL_SET_CREDENTIALS,
                toolsetDescriptor.getName(),
                parentFolders,
                toolsetDescriptor.getBucketName(),
                toolsetDescriptor.getBucketLocation(),
                false
        );
    }

}
