package com.epam.aidial.core.storage.cache;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.redisson.config.Credentials;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.function.Supplier;

@SuppressWarnings("checkstyle:LineLength")
public class AzureCredentialsResolver extends BaseCredentialsResolver {

    private static final long EXPIRATION_WINDOW_IN_SEC = 10;

    static final TokenRequestContext TOKEN_REQUEST_CONTEXT = new TokenRequestContext().addScopes("https://redis.azure.com/.default");

    private final DefaultAzureCredential defaultCredential;

    private final Supplier<OffsetDateTime> now;

    private OffsetDateTime expiresAt;

    private Credentials credentials;

    public AzureCredentialsResolver() {
        this.defaultCredential = new DefaultAzureCredentialBuilder().build();
        this.now = OffsetDateTime::now;
    }

    @VisibleForTesting
    AzureCredentialsResolver(DefaultAzureCredential defaultAzureCredential, Supplier<OffsetDateTime> now) {
        this.defaultCredential = defaultAzureCredential;
        this.now = now;
    }

    @Override
    protected Credentials resolve() {
        OffsetDateTime date = now.get().plusSeconds(EXPIRATION_WINDOW_IN_SEC);
        if (expiresAt == null || date.isAfter(expiresAt)) {
            AccessToken accessToken = defaultCredential.getTokenSync(TOKEN_REQUEST_CONTEXT);
            String token = accessToken.getToken();
            String userName = extractUsernameFromToken(token);
            expiresAt = accessToken.getExpiresAt();
            credentials = new Credentials(userName, token);
        }
        return credentials;
    }

    /**
     * @see <a href="https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/identity/azure-identity/src/samples/Azure-Cache-For-Redis/Jedis/Azure-AAD-Authentication-With-Jedis.md">Implementation details</a>
     */
    private static String extractUsernameFromToken(String token) {
        String[] parts = token.split("\\.");
        String base64 = parts[1];

        switch (base64.length() % 4) {
            case 2 -> base64 += "==";
            case 3 -> base64 += "=";
            default -> {
            }
        }

        byte[] jsonBytes = Base64.getDecoder().decode(base64);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        JsonObject jwt = JsonParser.parseString(json).getAsJsonObject();

        return jwt.get("oid").getAsString();
    }

}
