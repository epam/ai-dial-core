package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.keymanagement.KeyManagementService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ContentEncryptionKeyManagerImpl implements ContentEncryptionKeyManager {

    private final ResourceService resourceService;
    private final ContentEncryptionKeyGenerator contentEncryptionKeyGenerator;
    private final KeyManagementService keyManagementService;

    @Override
    public byte[] createKey(ResourceDescriptor cekDescriptor) {
        byte[] cek = contentEncryptionKeyGenerator.generate();
        byte[] encryptedCek = keyManagementService.encrypt(cek);
        resourceService.putResourceBytes(cekDescriptor, encryptedCek, EtagHeader.ANY);
        return cek;
    }

    @Override
    public byte[] getOrCreateKey(ResourceDescriptor cekDescriptor) {
        byte[] cek = getKey(cekDescriptor);
        if (cek == null) {
            cek = createKey(cekDescriptor);
        }
        return cek;
    }

    @Override
    public byte[] getKey(ResourceDescriptor cekDescriptor) {
        byte[] encryptedCek = resourceService.getResourceBytes(cekDescriptor);
        if (encryptedCek == null) {
            return null;
        }
        return keyManagementService.decrypt(encryptedCek);
    }

}
