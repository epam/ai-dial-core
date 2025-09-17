package com.epam.aidial.core.credentials.keymanagement;

import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.model.DecryptRequest;
import com.amazonaws.services.kms.model.DecryptResult;
import com.amazonaws.services.kms.model.EncryptRequest;
import com.amazonaws.services.kms.model.EncryptResult;

import java.nio.ByteBuffer;
import java.util.Objects;

public class AwsKeyManagementService implements KeyManagementService {

    private static final int KMS_DIRECT_ENCRYPT_LIMIT_BYTES = 4096;

    private final AWSKMS kms;
    private final String keyId;
    private final String encryptionAlgorithm;

    public AwsKeyManagementService(AWSKMS kms,
                                   String keyId,
                                   String encryptionAlgorithm) {

        this.kms = Objects.requireNonNull(kms, "kms");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.encryptionAlgorithm = encryptionAlgorithm;
    }

    @Override
    public byte[] encrypt(byte[] plain) {
        Objects.requireNonNull(plain, "plain");
        if (plain.length > KMS_DIRECT_ENCRYPT_LIMIT_BYTES) {
            throw new IllegalArgumentException("Plaintext too large for direct KMS Encrypt (max 4096 bytes).");
        }

        EncryptRequest req = new EncryptRequest()
                .withKeyId(keyId)
                .withEncryptionAlgorithm(encryptionAlgorithm)
                .withPlaintext(ByteBuffer.wrap(plain));

        EncryptResult result = kms.encrypt(req);
        return toByteArray(result.getCiphertextBlob());
    }

    @Override
    public byte[] decrypt(byte[] encrypted) {
        Objects.requireNonNull(encrypted, "encrypted");

        DecryptRequest req = new DecryptRequest()
                .withKeyId(keyId)
                .withEncryptionAlgorithm(encryptionAlgorithm)
                .withCiphertextBlob(ByteBuffer.wrap(encrypted));

        DecryptResult result = kms.decrypt(req);
        return toByteArray(result.getPlaintext());
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        ByteBuffer copy = buffer.asReadOnlyBuffer();
        copy.rewind();
        byte[] out = new byte[copy.remaining()];
        copy.get(out);
        return out;
    }

}
