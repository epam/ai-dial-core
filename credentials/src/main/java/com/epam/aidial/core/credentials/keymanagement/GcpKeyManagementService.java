package com.epam.aidial.core.credentials.keymanagement;

import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;

import java.util.Objects;

public class GcpKeyManagementService implements KeyManagementService {

    private final KeyManagementServiceClient kmsClient;
    private final String keyName;

    public GcpKeyManagementService(KeyManagementServiceClient kmsClient, String keyName) {

        this.kmsClient = Objects.requireNonNull(kmsClient, "kmsClient cannot be null.");
        this.keyName = Objects.requireNonNull(keyName, "keyName cannot be null.");
    }

    @Override
    public byte[] encrypt(byte[] plain) {
        Objects.requireNonNull(plain, "plain");

        EncryptRequest.Builder requestBuilder = EncryptRequest.newBuilder()
                .setName(keyName)
                .setPlaintext(ByteString.copyFrom(plain));

        ByteString encryptedData = kmsClient.encrypt(requestBuilder.build()).getCiphertext();
        return encryptedData.toByteArray();
    }

    @Override
    public byte[] decrypt(byte[] encrypted) {
        Objects.requireNonNull(encrypted, "encrypted");

        DecryptRequest.Builder requestBuilder = DecryptRequest.newBuilder()
                .setName(keyName)
                .setCiphertext(ByteString.copyFrom(encrypted));

        ByteString decryptedData = kmsClient.decrypt(requestBuilder.build()).getPlaintext();
        return decryptedData.toByteArray();
    }

}
