package com.epam.aidial.core.server.service.credentials.encryption.keymanagement;

import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.model.DecryptRequest;
import com.amazonaws.services.kms.model.DecryptResult;
import com.amazonaws.services.kms.model.EncryptRequest;
import com.amazonaws.services.kms.model.EncryptResult;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;

public class AwsKeyManagementService implements KeyManagementService {

    private static final int KMS_DIRECT_ENCRYPT_LIMIT_BYTES = 4096;

    private final AWSKMS kms;
    private final String keyId;
    private final Map<String, String> encryptionContext;

    public AwsKeyManagementService(AWSKMS kms, String keyId) {
        this(kms, keyId, Map.of());
    }

    public AwsKeyManagementService(AWSKMS kms, String keyId, Map<String, String> encryptionContext) {
        this.kms = Objects.requireNonNull(kms, "kms");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.encryptionContext = encryptionContext == null
                ? Map.of()
                : Map.copyOf(encryptionContext);
    }

    @Override
    public byte[] encode(byte[] plain) {
        Objects.requireNonNull(plain, "plain");
        if (plain.length > KMS_DIRECT_ENCRYPT_LIMIT_BYTES) {
            throw new IllegalArgumentException("Plaintext too large for direct KMS Encrypt (max 4096 bytes).");
        }

        EncryptRequest req = new EncryptRequest()
                .withKeyId(keyId)
                .withPlaintext(ByteBuffer.wrap(plain));
        if (!encryptionContext.isEmpty()) {
            req.setEncryptionContext(encryptionContext);
        }

        EncryptResult result = kms.encrypt(req);
        return toByteArray(result.getCiphertextBlob());
    }

    @Override
    public byte[] decode(byte[] encrypted) {
        Objects.requireNonNull(encrypted, "encrypted");

        DecryptRequest req = new DecryptRequest()
                .withCiphertextBlob(ByteBuffer.wrap(encrypted));
        if (!encryptionContext.isEmpty()) {
            req.setEncryptionContext(encryptionContext);
        }

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
