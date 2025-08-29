package com.epam.aidial.core.server.service.credentials.encryption.keymanagement;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.AWSKMSClientBuilder;
import io.vertx.core.json.JsonObject;
import lombok.experimental.UtilityClass;

@UtilityClass
public class KeyManagementServiceFactory {

    public KeyManagementService create(JsonObject toolsetSettings) {
        JsonObject security = toolsetSettings != null ? toolsetSettings.getJsonObject("security") : null;
        JsonObject kmsSettings = security != null ? security.getJsonObject("kms") : null;

        String keyId = kmsSettings != null ? kmsSettings.getString("keyId") : null;
        String region = kmsSettings != null ? kmsSettings.getString("region") : null;

        if (keyId != null && region != null) {
            AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
            AWSKMS kms = AWSKMSClientBuilder.standard()
                    .withCredentials(awsCredentialsProvider)
                    .withRegion(region)
                    .build();
            return new AwsKeyManagementService(kms, keyId);
        }

        if (keyId == null && region == null) {
            return new SimpleKeyManagementService();
        }

        throw new IllegalArgumentException("Both keyId and region must be specified");
    }

}
