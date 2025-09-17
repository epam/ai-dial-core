package com.epam.aidial.core.credentials.keymanagement;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.AWSKMSClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.epam.aidial.core.credentials.data.configuration.KmsSettings;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.util.Objects;

@UtilityClass
public class KeyManagementServiceFactory {

    public KeyManagementService create(KmsSettings kmsSettings) {
        if (kmsSettings == null || kmsSettings.getProvider() == null) {
            throw new IllegalArgumentException("toolsets.security.kms.provider must be specified");
        }

        String provider = kmsSettings.getProvider();
        if ("aws".equalsIgnoreCase(provider)) {
            return createAwsKeyManagementService(kmsSettings);
        } else if ("azure".equalsIgnoreCase(provider)) {
            return createAzureKeyManagementService(kmsSettings);
        } else if ("gcp".equalsIgnoreCase(provider)) {
            return createGcpKeyManagementService(kmsSettings);
        } else if ("unencrypted".equalsIgnoreCase(provider)) {
            return new SimpleKeyManagementService();
        }

        throw new IllegalArgumentException("Unknown toolsets.security.kms.provider: %s.".formatted(provider));
    }

    private KeyManagementService createAwsKeyManagementService(KmsSettings kmsSettings) {
        String keyId = kmsSettings.getKeyId();
        String region = kmsSettings.getRegion();
        String encryptionAlgorithm = kmsSettings.getEncryptionAlgorithm();
        Objects.requireNonNull(keyId, "keyId cannot be null.");
        Objects.requireNonNull(keyId, "region cannot be null.");

        AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        AWSKMS kms = AWSKMSClientBuilder.standard()
                .withCredentials(awsCredentialsProvider)
                .withRegion(region)
                .build();
        return new AwsKeyManagementService(kms, keyId, encryptionAlgorithm);
    }

    private static KeyManagementService createAzureKeyManagementService(KmsSettings kmsSettings) {
        String keyId = Objects.requireNonNull(kmsSettings.getKeyId(), "keyId cannot be null.");
        String encryptionAlgorithm = Objects.requireNonNull(kmsSettings.getEncryptionAlgorithm(),
                "encryptionAlgorithm cannot be null.");

        KeyWrapAlgorithm parsedEncryptionAlgorithm = KeyWrapAlgorithm.fromString(encryptionAlgorithm);

        CryptographyClient cryptographyClient = new CryptographyClientBuilder()
                .keyIdentifier(keyId)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();

        return new AzureKeyManagementService(cryptographyClient, parsedEncryptionAlgorithm);
    }

    @SneakyThrows
    private static KeyManagementService createGcpKeyManagementService(KmsSettings kmsSettings) {
        String keyId = Objects.requireNonNull(kmsSettings.getKeyId(), "keyId cannot be null.");

        KeyManagementServiceClient kmsClient = KeyManagementServiceClient.create();
        return new GcpKeyManagementService(kmsClient, keyId);
    }

}
