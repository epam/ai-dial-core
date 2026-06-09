package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CredentialEncryptionService {

    private final ContentEncryptionKeyService contentEncryptionKeyService;
    private final DataEncryptionService dataEncryptionService;

    /**
     * Encrypts the provided data using a content encryption key (CEK) associated with the specified bucket.
     *
     * <p>If encryption fails due to an invalid or unusable CEK (for example, if the key is corrupted or cannot be used),
     * a new CEK is created and the encryption is retried with the new key.
     *
     * @param bucketInfo Information about the bucket for which the encryption key is managed.
     * @param data The plaintext data to encrypt.
     * @param aad Optional additional authenticated data (AAD) for encryption; may be {@code null}.
     * @return The encrypted data as a byte array.
     * @throws SecurityException if encryption fails due to key management or cryptographic errors.
     */
    public byte[] encrypt(BucketInfo bucketInfo, byte[] data, byte[] aad) {
        return encryptOrDecrypt(bucketInfo, data, aad, true);
    }

    /**
     * Decrypts the provided data using a content encryption key (CEK) associated with the specified bucket.
     *
     * <p>If decryption fails due to an invalid or unusable CEK (for example, if the key is corrupted or cannot be used),
     * a new CEK is created and the decryption is retried with the new key.
     *
     * @param bucketInfo Information about the bucket for which the decryption key is managed.
     * @param data The encrypted data to decrypt.
     * @param aad Optional additional authenticated data (AAD) for decryption; may be {@code null}.
     * @return The decrypted data as a byte array.
     * @throws SecurityException if decryption fails due to key management or cryptographic errors.
     */
    public byte[] decrypt(BucketInfo bucketInfo, byte[] data, byte[] aad) {
        return encryptOrDecrypt(bucketInfo, data, aad, false);
    }

    /**
     * Minimum length, in bytes, of a payload produced by {@link #encrypt}; any shorter byte sequence
     * cannot be a ciphertext from this service. Delegates to {@link DataEncryptionService#minEncryptedLength()}.
     *
     * @return the minimum encrypted payload length in bytes
     */
    public int minEncryptedLength() {
        return dataEncryptionService.minEncryptedLength();
    }

    private byte[] encryptOrDecrypt(BucketInfo bucketInfo, byte[] data, byte[] aad, boolean isEncrypt) {
        byte[] existentContentEncryptionKey = contentEncryptionKeyService.getOrCreateKey(bucketInfo);
        return isEncrypt
                ? dataEncryptionService.encrypt(data, existentContentEncryptionKey, aad)
                : dataEncryptionService.decrypt(data, existentContentEncryptionKey, aad);
    }

}
