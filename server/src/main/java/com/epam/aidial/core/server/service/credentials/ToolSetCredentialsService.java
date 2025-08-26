package com.epam.aidial.core.server.service.credentials;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;

import java.util.Collections;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ToolSetCredentialsService {

    private final ResourceService resourceService;
    private final EncryptionService encryptionService;

    public void addToolSetCredentials(ToolSetCredentials toolSetCredentials) {
        if (toolSetCredentials.getCredentialsLevel() != CredentialsLevel.GLOBAL) {
            throw new NotImplementedException("Only GLOBAL credentials level is supported for now.");
        }
        ResourceDescriptor resourceDescriptor = getCredentialsDescriptor(toolSetCredentials.getToolSetName());
        String body = ProxyUtil.convertToString(toolSetCredentials);
        resourceService.putResource(resourceDescriptor, body, EtagHeader.ANY);
    }

    public List<ToolSetCredentials> getAllToolSetCredentials(String toolSetName) {
        ResourceDescriptor resourceDescriptor = getCredentialsDescriptor(toolSetName);
        String body = resourceService.getResource(resourceDescriptor);
        if (body != null) {
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
            return (credentials == null) ? null : ProxyUtil.convertToString(credentials);
        });
    }

    private ResourceDescriptor getCredentialsDescriptor(String toolSetName) {
        ResourceDescriptor toolsetDescriptor = ResourceDescriptorFactory.fromAnyUrl(toolSetName, encryptionService);
        return new ResourceDescriptor(
                ResourceTypes.TOOL_SET_CREDENTIALS,
                toolsetDescriptor.getName(),
                toolsetDescriptor.getParentFolders(),
                toolsetDescriptor.getBucketName(),
                toolsetDescriptor.getBucketLocation(),
                false
        );
    }

}
