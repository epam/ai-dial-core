package com.epam.aidial.core.server.service.credentials.encryption.keymanagement;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.AWSKMSClientBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

@UtilityClass
public class KeyManagementServiceFactory {

    public KeyManagementService create(JsonNode conf) {
        AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        AWSKMS kms = AWSKMSClientBuilder.standard()
                .withCredentials(awsCredentialsProvider)
                .withRegion("us-east-1") // todo: region validation
                .build();
        String keyId = ""; // todo: configurable

        return new AwsKeyManagementService(kms, keyId);
    }

}
