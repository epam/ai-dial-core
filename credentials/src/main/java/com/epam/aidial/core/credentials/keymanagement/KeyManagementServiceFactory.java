package com.epam.aidial.core.credentials.keymanagement;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.AWSKMSClientBuilder;
import com.epam.aidial.core.credentials.data.configuration.KmsSettings;
import lombok.experimental.UtilityClass;

@UtilityClass
public class KeyManagementServiceFactory {

    public KeyManagementService create(KmsSettings kmsSettings) {
        if (kmsSettings == null || kmsSettings.getProvider() == null) {
            throw new IllegalArgumentException("toolsets.security.kms.provider must be specified");
        }

        String provider = kmsSettings.getProvider();
        if ("aws".equalsIgnoreCase(provider)) {
            return createAwsKeyManagementService(kmsSettings);
        } else if ("unencrypted".equalsIgnoreCase(provider)) {
            return new SimpleKeyManagementService();
        }

        throw new IllegalArgumentException("Unknown toolsets.security.kms.provider: %s.".formatted(provider));
    }

    private KeyManagementService createAwsKeyManagementService(KmsSettings kmsSettings) {
        String keyId = kmsSettings.getKeyId();
        String region = kmsSettings.getRegion();
        if (keyId == null || region == null) {
            throw new IllegalArgumentException("Both keyId and region must be specified");
        }

        AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        AWSKMS kms = AWSKMSClientBuilder.standard()
                .withCredentials(awsCredentialsProvider)
                .withRegion(region)
                .build();
        return new AwsKeyManagementService(kms, keyId);
    }

}
