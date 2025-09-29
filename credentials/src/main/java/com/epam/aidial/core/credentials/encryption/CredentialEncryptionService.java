package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CredentialEncryptionService {

    private final ContentEncryptionKeyService contentEncryptionKeyService;
    private final DataEncryptionService dataEncryptionService;

    public byte[] encrypt(BucketInfo bucketInfo, byte[] data, byte[] aad) {
        try {
            byte[] contentEncryptionKey = contentEncryptionKeyService.getOrCreateKey(bucketInfo);
            return dataEncryptionService.encrypt(data, contentEncryptionKey, aad);
        } catch (RuntimeException e) {
            throw new EncryptionException("Failed to encrypt data", e);
        }
    }

    public byte[] decrypt(BucketInfo bucketInfo, byte[] data, byte[] aad) {
        try {
            byte[] contentEncryptionKey = contentEncryptionKeyService.getOrCreateKey(bucketInfo);
            return dataEncryptionService.decrypt(data, contentEncryptionKey, aad);
        } catch (RuntimeException e) {
            throw new EncryptionException("Failed to decrypt data", e);
        }
    }

}
