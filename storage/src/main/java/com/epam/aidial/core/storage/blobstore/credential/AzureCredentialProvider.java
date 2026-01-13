package com.epam.aidial.core.storage.blobstore.credential;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.domain.Credentials;

import java.time.OffsetDateTime;
import java.util.function.Supplier;

@Slf4j
public class AzureCredentialProvider implements CredentialProvider {

    private static final long EXPIRATION_WINDOW_IN_SEC = 10;

    private Credentials credentials;

    private DefaultAzureCredential defaultCredential;

    private volatile AccessToken accessToken;

    private TokenRequestContext tokenRequestContext;

    private Supplier<OffsetDateTime> now;

    public AzureCredentialProvider(String identity, String secret) {
        if (identity != null && secret != null) {
            this.credentials = new Credentials(identity, secret);
        } else {
            this.now = OffsetDateTime::now;
            defaultCredential = new DefaultAzureCredentialBuilder().build();
            tokenRequestContext = (new TokenRequestContext()).addScopes("https://storage.azure.com/.default");
        }
    }

    @VisibleForTesting
    AzureCredentialProvider(DefaultAzureCredential defaultAzureCredential, TokenRequestContext tokenRequestContext, Supplier<OffsetDateTime> now) {
        this.defaultCredential = defaultAzureCredential;
        this.tokenRequestContext = tokenRequestContext;
        this.now = now;
    }

    @Override
    public Credentials getCredentials() {
        if (credentials != null) {
            return credentials;
        }
        return getTemporaryCredentials();
    }

    private Credentials getTemporaryCredentials() {
        OffsetDateTime date = now.get().plusSeconds(EXPIRATION_WINDOW_IN_SEC);
        if (accessToken == null || date.isAfter(accessToken.getExpiresAt())) {
            log.debug("Start requesting temporary token from Azure Identity");
            accessToken = defaultCredential.getTokenSync(tokenRequestContext);
            log.debug("Received temporary token from Azure Identity");
        }
        return new Credentials("", accessToken.getToken());
    }
}
