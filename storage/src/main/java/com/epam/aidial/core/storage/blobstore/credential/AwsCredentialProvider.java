package com.epam.aidial.core.storage.blobstore.credential;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceAsyncClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
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
        if (true) {
            AWSCredentials awsCredentials = new BasicSessionCredentials(System.getenv("AWS_ACCESS_KEY_ID"),
                    System.getenv("AWS_SECRET_ACCESS_KEY"),
                    System.getenv("AWS_SESSION_TOKEN"));
            AWSStaticCredentialsProvider provider = new AWSStaticCredentialsProvider(awsCredentials);
            AWSSecurityTokenService stsClient = AWSSecurityTokenServiceAsyncClientBuilder.standard()
                    .withCredentials(provider)
                    .withRegion("eu-north-1")
                    .build();

            AssumeRoleRequest assumeRoleRequest = new AssumeRoleRequest().withDurationSeconds(3600)
                    .withRoleArn("arn:aws:iam::725751206603:role/dial")
                    .withRoleSessionName("CloudWatch_Session");

            AssumeRoleResult assumeRoleResult = stsClient.assumeRole(assumeRoleRequest);
            com.amazonaws.services.securitytoken.model.Credentials creds = assumeRoleResult.getCredentials();
            credentials =  SessionCredentials.builder()
                    .accessKeyId(creds.getAccessKeyId())
                    .secretAccessKey(creds.getSecretAccessKey())
                    .sessionToken(creds.getSessionToken()).build();
            return credentials;
        } else {
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

}
