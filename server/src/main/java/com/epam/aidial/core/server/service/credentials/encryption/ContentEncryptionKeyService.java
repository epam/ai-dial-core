package com.epam.aidial.core.server.service.credentials.encryption;

import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ContentEncryptionKeyService {

    private final static String CEK_FILENAME = "cek";

    private final ContentEncryptionKeyManager contentEncryptionKeyManager;
    private final EncryptionService encryptionService;

    public byte[] getOrCreateKey(String toolSetName) {
        ResourceDescriptor cekDescription = getContentEncryptionKeyDescriptor(toolSetName);
        return contentEncryptionKeyManager.getOrCreateKey(cekDescription);
    }

    public byte[] getKey(String toolSetName) {
        ResourceDescriptor cekDescriptor = getContentEncryptionKeyDescriptor(toolSetName);
        return contentEncryptionKeyManager.getKey(cekDescriptor);
    }

    private ResourceDescriptor getContentEncryptionKeyDescriptor(String toolSetName) {
        ResourceDescriptor toolsetDescriptor = ResourceDescriptorFactory.fromAnyUrl(toolSetName, encryptionService);

        return new ResourceDescriptor(
                ResourceTypes.TOOL_SET_CREDENTIALS,
                CEK_FILENAME,
                toolsetDescriptor.getParentFolders(),
                toolsetDescriptor.getBucketName(),
                toolsetDescriptor.getBucketLocation(),
                false
        );
    }

}
