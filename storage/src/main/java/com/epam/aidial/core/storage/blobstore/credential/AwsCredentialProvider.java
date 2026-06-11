package com.epam.aidial.core.storage.blobstore.credential;

import lombok.extern.slf4j.Slf4j;
import org.jclouds.aws.domain.SessionCredentials;
import org.jclouds.domain.Credentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

@Slf4j
public class AwsCredentialProvider implements CredentialProvider {

    private Credentials credentials;
    private DefaultCredentialsProvider providerChain;

    public AwsCredentialProvider(String identity, String secret) {
        if (identity != null && secret != null) {
            this.credentials = new Credentials(identity, secret);
        } else {
            providerChain = DefaultCredentialsProvider.create();
        }
    }

    @Override
    public Credentials getCredentials() {
        if (credentials != null) {
            return credentials;
        }
        log.debug("Start requesting temporary token from AWS Identity");
        AwsCredentials awsCredentials = providerChain.resolveCredentials();
        log.debug("Received temporary token from AWS Identity");
        if (awsCredentials instanceof AwsSessionCredentials awsSessionCredentials) {
            return SessionCredentials.builder()
                    .accessKeyId(awsSessionCredentials.accessKeyId())
                    .secretAccessKey(awsSessionCredentials.secretAccessKey())
                    .sessionToken(awsSessionCredentials.sessionToken()).build();
        } else {
            return new Credentials(awsCredentials.accessKeyId(), awsCredentials.secretAccessKey());
        }
    }
}
