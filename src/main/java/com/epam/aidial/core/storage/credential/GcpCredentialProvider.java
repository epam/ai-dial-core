package com.epam.aidial.core.storage.credential;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.io.Files;
import lombok.SneakyThrows;
import org.jclouds.domain.Credentials;
import org.jclouds.googlecloud.GoogleCredentialsFromJson;

import java.io.File;
import java.time.Instant;
import java.util.Date;

import static java.nio.charset.StandardCharsets.UTF_8;

public class GcpCredentialProvider implements CredentialProvider {

    private static final long EXPIRATION_WINDOW_IN_MS = 10_000;

    private Credentials credentials;

    private AccessToken accessToken;

    private GoogleCredentials googleCredentials;

    /**
     *
     * @param identity client email address
     * @param credential could be private key or path to JSON file where the private resides
     */
    @SneakyThrows
    public GcpCredentialProvider(String identity, String credential) {
        if (identity != null && credential != null) {
            // credential is a client email address
            this.credentials = new Credentials(identity, credential);
        } else if (credential != null) {
            // credential is a path to private key JSON file
            this.credentials = getCredentialsFromJsonKeyFile(credential);
        } else {
            // use temporary credential provided by GCP
            this.googleCredentials = GoogleCredentials.getApplicationDefault();
        }
    }

    @Override
    public Credentials getCredentials() {
        if (credentials != null) {
            return credentials;
        }
        return getTemporaryCredentials();
    }

    @SneakyThrows
    private synchronized Credentials getTemporaryCredentials() {
        Date expireAt = Date.from(Instant.ofEpochMilli(System.currentTimeMillis() - EXPIRATION_WINDOW_IN_MS));
        if (accessToken == null || expireAt.after(accessToken.getExpirationTime())) {
            accessToken = googleCredentials.refreshAccessToken();
        }
        return new Credentials("", accessToken.getTokenValue());
    }

    @SneakyThrows
    private static Credentials getCredentialsFromJsonKeyFile(String filename) {
        String fileContents = Files.asCharSource(new File(filename), UTF_8).read();
        GoogleCredentialsFromJson credentialSupplier = new GoogleCredentialsFromJson(fileContents);
        return credentialSupplier.get();
    }
}
