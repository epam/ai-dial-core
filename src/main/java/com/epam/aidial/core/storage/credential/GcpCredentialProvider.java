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
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

public class GcpCredentialProvider implements CredentialProvider {

    private static final long EXPIRATION_WINDOW_IN_MS = 10_000;

    private Credentials credentials;

    private AccessToken accessToken;

    public GcpCredentialProvider(String pathToPrivateKey) {
        if (pathToPrivateKey != null) {
            this.credentials = getCredentialsFromJsonKeyFile(Objects.requireNonNull(pathToPrivateKey, "Path to JSON key file must be provided"));
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
        GoogleCredentials googleCredentials = GoogleCredentials.getApplicationDefault();
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
