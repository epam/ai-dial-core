package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CredentialEncryptionService {

    private final ContentEncryptionKeyService contentEncryptionKeyService;
    private final DataEncryptionService dataEncryptionService;

    public byte[] encrypt(BucketInfo bucketInfo, byte[] data, byte[] aad) {
        byte[] contentEncryptionKey = contentEncryptionKeyService.getOrCreateKey(bucketInfo);
        return dataEncryptionService.encrypt(data, contentEncryptionKey, aad);
    }

    public byte[] decrypt(BucketInfo bucketInfo, byte[] data, byte[] aad) {
        byte[] contentEncryptionKey = contentEncryptionKeyService.getOrCreateKey(bucketInfo);
        return dataEncryptionService.decrypt(data, contentEncryptionKey, aad);
    }

}
