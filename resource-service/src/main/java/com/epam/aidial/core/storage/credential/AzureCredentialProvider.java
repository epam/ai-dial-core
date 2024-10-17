package com.epam.aidial.core.storage.credential;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.jclouds.domain.Credentials;

import java.time.OffsetDateTime;

public class AzureCredentialProvider implements CredentialProvider {

    private static final long EXPIRATION_WINDOW_IN_SEC = 10;

    private Credentials credentials;

    private DefaultAzureCredential defaultCredential;

    private AccessToken accessToken;

    private TokenRequestContext tokenRequestContext;

    public AzureCredentialProvider(String identity, String secret) {
        if (identity != null && secret != null) {
            this.credentials = new Credentials(identity, secret);
        } else {
            defaultCredential = new DefaultAzureCredentialBuilder().build();
            tokenRequestContext = (new TokenRequestContext()).addScopes("https://storage.azure.com/.default");
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
        OffsetDateTime date = OffsetDateTime.now().minusSeconds(EXPIRATION_WINDOW_IN_SEC);
        if (accessToken == null || date.isAfter(accessToken.getExpiresAt())) {
            accessToken = defaultCredential.getTokenSync(tokenRequestContext);
        }
        return new Credentials("", accessToken.getToken());
    }
}
