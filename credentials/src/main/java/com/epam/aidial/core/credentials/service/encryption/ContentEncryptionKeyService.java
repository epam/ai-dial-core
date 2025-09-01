package com.epam.aidial.core.credentials.service.encryption;

import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ContentEncryptionKeyService {

    private final static String CEK_FILENAME = "cek";

    private final ContentEncryptionKeyManager contentEncryptionKeyManager;

    public byte[] getOrCreateKey(ResourceDescriptor resourceDescriptor) {
        ResourceDescriptor cekDescription = getContentEncryptionKeyDescriptor(resourceDescriptor);
        return contentEncryptionKeyManager.getOrCreateKey(cekDescription);
    }

    public byte[] getKey(ResourceDescriptor resourceDescriptor) {
        ResourceDescriptor cekDescriptor = getContentEncryptionKeyDescriptor(resourceDescriptor);
        return contentEncryptionKeyManager.getKey(cekDescriptor);
    }

    private ResourceDescriptor getContentEncryptionKeyDescriptor(ResourceDescriptor resourceDescriptor) {
        return new ResourceDescriptor(
                ResourceTypes.TOOL_SET_CREDENTIALS,
                CEK_FILENAME,
                resourceDescriptor.getParentFolders(),
                resourceDescriptor.getBucketName(),
                resourceDescriptor.getBucketLocation(),
                false
        );
    }

}
