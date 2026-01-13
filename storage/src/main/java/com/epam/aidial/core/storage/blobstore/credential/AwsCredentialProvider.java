package com.epam.aidial.core.storage.blobstore.credential;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.aws.domain.SessionCredentials;
import org.jclouds.domain.Credentials;

@Slf4j
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
        log.debug("Start requesting temporary token from AWS Identity");
        AWSCredentials awsCredentials = providerChain.getCredentials();
        log.debug("Received temporary token from AWS Identity");
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
