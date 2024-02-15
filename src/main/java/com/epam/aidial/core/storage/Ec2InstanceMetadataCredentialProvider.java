package com.epam.aidial.core.storage;

import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.aws.domain.SessionCredentials;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class Ec2InstanceMetadataCredentialProvider implements CredentialProvider {

    private static final String EC2_INSTANCE_METADATA_BASE_URL = "http://169.254.169.254/latest/";
    private static final String EC2_INSTANCE_METADATA_CREDENTIALS_URL = EC2_INSTANCE_METADATA_BASE_URL + "meta-data/iam/security-credentials/";
    private static final String EC2_TOKEN_TTL_HEADER_NAME = "X-aws-ec2-metadata-token-ttl-seconds";
    private static final String EC2_METADATA_TOKEN_HEADER_NAME = "X-aws-ec2-metadata-token";

    private final HttpClient httpClient;
    private final Lock lock = new ReentrantLock();

    private SessionCredentials credentials;

    public Ec2InstanceMetadataCredentialProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public Ec2InstanceMetadataCredentialProvider() {
        this(HttpClient.newHttpClient());
    }


    @Override
    public SessionCredentials getCredentials() {
        lock.lock();
        try {
            // if token present and not expired
            if (credentials != null && credentials.getExpiration().isPresent() && credentials.getExpiration().get().after(Date.from(Instant.now()))) {
                return credentials;
            }
            String token = getToken();
            String roleName = getRoleName(token);
            AwsCredentials awsCredentials = getAwsCredentials(token, roleName);

            credentials = SessionCredentials.builder()
                    .accessKeyId(awsCredentials.getAccessKeyId())
                    .expiration(Date.from(Instant.parse(awsCredentials.getExpiration())))
                    .secretAccessKey(awsCredentials.getSecretAccessKey())
                    .sessionToken(awsCredentials.getToken()).build();

            return credentials;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }  finally {
            lock.unlock();
        }
    }

    private String getToken() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EC2_INSTANCE_METADATA_BASE_URL + "api/token"))
                .setHeader(EC2_TOKEN_TTL_HEADER_NAME, "21600")
                .timeout(Duration.of(10, ChronoUnit.SECONDS))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String getRoleName(String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EC2_INSTANCE_METADATA_CREDENTIALS_URL))
                .setHeader(EC2_METADATA_TOKEN_HEADER_NAME, token)
                .timeout(Duration.of(10, ChronoUnit.SECONDS))
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private AwsCredentials getAwsCredentials(String token, String roleName) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EC2_INSTANCE_METADATA_CREDENTIALS_URL + roleName))
                .setHeader(EC2_METADATA_TOKEN_HEADER_NAME, token)
                .timeout(Duration.of(10, ChronoUnit.SECONDS))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return ProxyUtil.convertToObject(response.body(), AwsCredentials.class);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    static class AwsCredentials {
        String code;
        String lastUpdated;
        String type;
        String accessKeyId;
        String secretAccessKey;
        String token;
        String expiration;
    }
}
