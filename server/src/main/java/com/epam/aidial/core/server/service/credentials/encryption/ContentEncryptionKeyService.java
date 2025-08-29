package com.epam.aidial.core.server.service.credentials.encryption;

import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.credentials.encryption.keymanagement.KeyManagementService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ContentEncryptionKeyService {

    private final static String CEK_FILENAME = "cek";

    private final ResourceService resourceService;
    private final EncryptionService encryptionService;
    private final ContentEncryptionKeyGenerator contentEncryptionKeyGenerator;
    private final KeyManagementService keyManagementService;

    public byte[] createContentEncryptionKey(String toolSetName) {
        byte[] cek = contentEncryptionKeyGenerator.generate();
        ResourceDescriptor cekDescription = getContentEncryptionKeyDescriptor(toolSetName);
        byte[] encryptedCek = keyManagementService.encode(cek);
        resourceService.putResourceBytes(cekDescription, encryptedCek, EtagHeader.ANY);
        return cek;
    }

    public byte[] getOrCreateContentEncryptionKey(String toolSetName) {
        byte[] cek = getContentEncryptionKey(toolSetName);
        if (cek == null) {
            cek = createContentEncryptionKey(toolSetName);
        }
        return cek;
    }

    public byte[] getContentEncryptionKey(String toolSetName) {
        ResourceDescriptor cekDescriptor = getContentEncryptionKeyDescriptor(toolSetName);
        byte[] encryptedCek = resourceService.getResourceBytes(cekDescriptor);
        if (encryptedCek == null) {
            return null;
        }
        return keyManagementService.decode(encryptedCek);
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
