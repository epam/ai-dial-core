package com.epam.aidial.core.storage.credential;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.jclouds.domain.Credentials;

import java.time.Instant;
import java.time.OffsetDateTime;

public class AzureCredentialProvider implements CredentialProvider {

    private static final long MARGIN_EXPIRATION_IN_MS = 10_000;

    private Credentials credentials;

    private DefaultAzureCredential defaultCredential;

    private AccessToken accessToken;

    public AzureCredentialProvider(String identity, String secret) {
        if (identity != null && secret != null) {
            this.credentials = new Credentials(identity, secret);
        } else {
            defaultCredential = new DefaultAzureCredentialBuilder().build();
        }
    }

    @Override
    public Credentials getCredentials() {
        if (credentials != null) {
            return credentials;
        }
        return getTemporaryCredentials();
    }

    private synchronized Credentials getTemporaryCredentials() {
        OffsetDateTime date = OffsetDateTime.from(Instant.ofEpochMilli(System.currentTimeMillis() - MARGIN_EXPIRATION_IN_MS));
        if (accessToken == null || date.isAfter(accessToken.getExpiresAt())) {
            accessToken = defaultCredential.getTokenSync(new TokenRequestContext());
        }
        return new Credentials("", accessToken.getToken());
    }
}
