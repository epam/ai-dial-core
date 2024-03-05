package com.epam.aidial.core.storage.credential;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import org.jclouds.aws.domain.SessionCredentials;
import org.jclouds.domain.Credentials;

public class AwsCredentialProvider implements CredentialProvider {

    private Credentials credentials;
    private DefaultAWSCredentialsProviderChain providerChain;

    public AwsCredentialProvider(String identity, String secret) {
        if (identity != null && secret != null) {
            this.credentials = new Credentials(identity, secret);
        } else {
            providerChain = new DefaultAWSCredentialsProviderChain();
        }
    }

    @Override
    public Credentials getCredentials() {
        if (credentials != null) {
            return credentials;
        }
        AWSCredentials awsCredentials = providerChain.getCredentials();
        if (awsCredentials instanceof AWSSessionCredentials awsSessionCredentials) {
            return SessionCredentials.builder()
                    .accessKeyId(awsSessionCredentials.getAWSAccessKeyId())
                    .secretAccessKey(awsSessionCredentials.getAWSSecretKey())
                    .sessionToken(awsSessionCredentials.getSessionToken()).build();
        } else {
            return new Credentials(awsCredentials.getAWSAccessKeyId(), awsCredentials.getAWSSecretKey());
        }
    }
}
