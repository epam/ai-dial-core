package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.keymanagement.KeyManagementService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicReference;

@RequiredArgsConstructor
public class ContentEncryptionKeyManagerImpl implements ContentEncryptionKeyManager {

    private final ResourceService resourceService;
    private final ContentEncryptionKeyGenerator contentEncryptionKeyGenerator;
    private final KeyManagementService keyManagementService;

    @Override
    public byte[] getOrCreateKey(ResourceDescriptor cekDescriptor) {
        AtomicReference<byte[]> cekHolder = new AtomicReference<>();
        resourceService.computeResourceBytes(cekDescriptor, encryptedCek -> {
            if (encryptedCek != null) {
                byte[] cek = keyManagementService.decrypt(encryptedCek);
                cekHolder.set(cek);
                return encryptedCek;
            } else {
                byte[] cek = contentEncryptionKeyGenerator.generate();
                cekHolder.set(cek);
                return keyManagementService.encrypt(cek);
            }
        });
        return cekHolder.get();
    }

}
