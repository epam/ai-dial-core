package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.keymanagement.KeyManagementService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.mutable.MutableObject;

/**
 * Default implementation of {@link ContentEncryptionKeyManager}.
 *
 * <p>Retrieves an encrypted CEK from a {@link ResourceService}, decrypts it using
 * a {@link KeyManagementService}, and returns the decrypted CEK.
 *
 * <p>If the CEK does not exist, generates a new one via
 * {@link ContentEncryptionKeyGenerator}, encrypts it with the KMS, and stores
 * it in the {@link ResourceService}.
 */
@RequiredArgsConstructor
public class ContentEncryptionKeyManagerImpl implements ContentEncryptionKeyManager {

    private final ResourceService resourceService;
    private final ContentEncryptionKeyGenerator contentEncryptionKeyGenerator;
    private final KeyManagementService keyManagementService;

    @Override
    public byte[] getOrCreateKey(ResourceDescriptor cekDescriptor) {
        MutableObject<byte[]> cekHolder = new MutableObject<>();
        resourceService.computeResourceBytes(cekDescriptor, encryptedCek -> {
            if (encryptedCek != null) {
                byte[] cek = keyManagementService.decrypt(encryptedCek);
                cekHolder.setValue(cek);
                return encryptedCek;
            } else {
                byte[] cek = contentEncryptionKeyGenerator.generate();
                cekHolder.setValue(cek);
                return keyManagementService.encrypt(cek);
            }
        });
        return cekHolder.get();
    }

}
