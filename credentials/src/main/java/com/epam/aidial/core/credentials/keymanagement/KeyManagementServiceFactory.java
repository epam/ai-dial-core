package com.epam.aidial.core.credentials.keymanagement;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.epam.aidial.core.credentials.data.configuration.KmsSettings;
import com.epam.aidial.core.storage.tracing.BlockingCallTracer;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

import java.util.Objects;

@UtilityClass
public class KeyManagementServiceFactory {

    public KeyManagementService create(KmsSettings kmsSettings, BlockingCallTracer blockingCallTracer) {
        if (kmsSettings == null || kmsSettings.getProvider() == null || "unencrypted".equals(kmsSettings.getProvider())) {
            return new SimpleKeyManagementService();
        }

        String provider = kmsSettings.getProvider();
        KeyManagementService service;
        if ("aws".equalsIgnoreCase(provider)) {
            service = createAwsKeyManagementService(kmsSettings);
        } else if ("azure".equalsIgnoreCase(provider)) {
            service = createAzureKeyManagementService(kmsSettings);
        } else if ("gcp".equalsIgnoreCase(provider)) {
            service = createGcpKeyManagementService(kmsSettings);
        } else {
            throw new IllegalArgumentException("Unknown toolsets.security.kms.provider: %s.".formatted(provider));
        }
        return traced(service, blockingCallTracer);
    }

    private static KeyManagementService traced(KeyManagementService service, BlockingCallTracer blockingCallTracer) {
        return new KeyManagementService() {
            @Override
            public byte[] encrypt(byte[] plain) {
                return blockingCallTracer.trace("kms.encrypt", () -> service.encrypt(plain));
            }

            @Override
            public byte[] decrypt(byte[] encrypted) {
                return blockingCallTracer.trace("kms.decrypt", () -> service.decrypt(encrypted));
            }
        };
    }

    private KeyManagementService createAwsKeyManagementService(KmsSettings kmsSettings) {
        String keyId = Objects.requireNonNull(kmsSettings.getKeyId(), "keyId cannot be null.");
        String region = Objects.requireNonNull(kmsSettings.getRegion(), "region cannot be null.");
        String encryptionAlgorithm = kmsSettings.getEncryptionAlgorithm();

        KmsClient kms = KmsClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region))
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

        KeyManagementServiceSettings settings = KeyManagementServiceSettings.newHttpJsonBuilder().build();
        KeyManagementServiceClient kmsClient = KeyManagementServiceClient.create(settings);
        return new GcpKeyManagementService(kmsClient, keyId);
    }

}
