package com.epam.aidial.core.credentials.keymanagement;

import com.epam.aidial.core.credentials.exception.CekEncryptionException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;
import software.amazon.awssdk.services.kms.model.IncorrectKeyException;
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException;
import software.amazon.awssdk.services.kms.model.InvalidKeyUsageException;
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException;

import java.util.Objects;

public class AwsKeyManagementService implements KeyManagementService {

    private static final int KMS_DIRECT_ENCRYPT_LIMIT_BYTES = 4096;

    private final KmsClient kms;
    private final String keyId;
    private final String encryptionAlgorithm;

    public AwsKeyManagementService(KmsClient kms,
                                   String keyId,
                                   String encryptionAlgorithm) {

        this.kms = Objects.requireNonNull(kms, "kms");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.encryptionAlgorithm = encryptionAlgorithm;
    }

    @Override
    public byte[] encrypt(byte[] plain) {
        try {
            Objects.requireNonNull(plain, "plain");
            if (plain.length > KMS_DIRECT_ENCRYPT_LIMIT_BYTES) {
                throw new IllegalArgumentException("Plaintext too large for direct KMS Encrypt (max 4096 bytes).");
            }

            EncryptRequest req = EncryptRequest.builder()
                    .keyId(keyId)
                    .encryptionAlgorithm(encryptionAlgorithm)
                    .plaintext(SdkBytes.fromByteArray(plain))
                    .build();

            EncryptResponse result = kms.encrypt(req);
            return result.ciphertextBlob().asByteArray();
        } catch (InvalidKeyUsageException | KmsInvalidStateException e) {
            throw new CekEncryptionException("Encryption error", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] encrypted) {
        try {
            Objects.requireNonNull(encrypted, "encrypted");

            DecryptRequest req = DecryptRequest.builder()
                    .keyId(keyId)
                    .encryptionAlgorithm(encryptionAlgorithm)
                    .ciphertextBlob(SdkBytes.fromByteArray(encrypted))
                    .build();

            DecryptResponse result = kms.decrypt(req);
            return result.plaintext().asByteArray();
        } catch (InvalidCiphertextException | InvalidKeyUsageException
                 | IncorrectKeyException | KmsInvalidStateException e) {
            throw new CekEncryptionException("Decryption error", e);
        }
    }

}
