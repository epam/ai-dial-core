package com.epam.aidial.core;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;

public class GetToken {
    public static void main(String ...args) {
        DefaultAzureCredential defaultCredential = new DefaultAzureCredentialBuilder().build();
        AccessToken accessToken = defaultCredential.getTokenSync(new TokenRequestContext());
        System.out.println("token %s".formatted(accessToken.getToken()));
        System.out.println("expired at %s".formatted(accessToken.getExpiresAt()));
    }
}
