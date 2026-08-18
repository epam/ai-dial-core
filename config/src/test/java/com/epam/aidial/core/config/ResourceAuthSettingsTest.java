package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceAuthSettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ResourceAuthSettings oauthSettings(String clientSecret) {
        return ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId("client-id")
                .clientSecret(clientSecret)
                .codeVerifier("code-verifier")
                .build();
    }

    @Test
    void testWithoutSecretsDropsCredentialMaterialAndHintByDefault() {
        ResourceAuthSettings safe = oauthSettings("my-client-secret").withoutSecrets();

        assertNull(safe.getClientSecret());
        assertNull(safe.getCodeVerifier());
        assertNull(safe.getClientSecretHint());
        assertEquals("client-id", safe.getClientId());
    }

    @Test
    void testWithoutSecretsLeavesReceiverUntouched() {
        ResourceAuthSettings settings = oauthSettings("my-client-secret");
        settings.withoutSecretsKeepingHint();

        assertEquals("my-client-secret", settings.getClientSecret());
        assertNull(settings.getClientSecretHint());
    }

    @Test
    void testHintIsTheTrailingCharactersOnly() {
        ResourceAuthSettings safe = oauthSettings("my-client-secret").withoutSecretsKeepingHint();

        assertEquals("cret", safe.getClientSecretHint());
        assertNull(safe.getClientSecret());
        assertNull(safe.getCodeVerifier());
    }

    @Test
    void testHintWidthIsConstantForRealisticSecrets() {
        // Every value at or beyond FULL_HINT_SECRET_LENGTH reveals the same count, so among realistic
        // secrets (issuers land at 32-64 characters) the hint says nothing about the secret's size.
        assertEquals(ResourceAuthSettings.HINT_LENGTH,
                oauthSettings("0123456789ab").withoutSecretsKeepingHint().getClientSecretHint().length());
        assertEquals(ResourceAuthSettings.HINT_LENGTH,
                oauthSettings("x".repeat(64)).withoutSecretsKeepingHint().getClientSecretHint().length());
    }

    @Test
    void testShortSecretRevealsFewerCharacters() {
        // 8-11 characters: below what ClientSecretValidation lets in today, so only legacy values land here.
        // The fragment shrinks rather than approaching the whole secret.
        assertEquals("ef", oauthSettings("012345ef").withoutSecretsKeepingHint().getClientSecretHint());
        assertEquals("9a", oauthSettings("0123456789a").withoutSecretsKeepingHint().getClientSecretHint());
    }

    @Test
    void testNoHintForSecretTooShortToPartiallyReveal() {
        assertNull(oauthSettings("0123456").withoutSecretsKeepingHint().getClientSecretHint());
        assertNull(oauthSettings("x").withoutSecretsKeepingHint().getClientSecretHint());
    }

    @Test
    void testNoHintWithoutStoredSecret() {
        assertNull(oauthSettings(null).withoutSecretsKeepingHint().getClientSecretHint());
    }

    @Test
    void testClearComputedFieldsDropsStatusesAndHint() {
        ResourceAuthSettings settings = oauthSettings("my-client-secret");
        settings.setGlobalAuthStatus(ResourceAuthStatus.SIGNED_IN);
        settings.setUserLevelAuthStatus(ResourceAuthStatus.SIGNED_IN);
        settings.setAppLevelAuthStatus(ResourceAuthStatus.SIGNED_IN);
        settings.setClientSecretHint("cret");

        settings.clearComputedFields();

        assertNull(settings.getGlobalAuthStatus());
        assertNull(settings.getUserLevelAuthStatus());
        assertNull(settings.getAppLevelAuthStatus());
        assertNull(settings.getClientSecretHint());
        assertEquals("my-client-secret", settings.getClientSecret());
    }

    @Test
    void testHintIsSerializedButNeverAcceptedFromClients() throws Exception {
        JsonNode serialized = MAPPER.valueToTree(oauthSettings("my-client-secret").withoutSecretsKeepingHint());
        assertEquals("cret", serialized.get("client_secret_hint").asText());
        assertTrue(serialized.get("client_secret") == null || serialized.get("client_secret").isNull());

        ResourceAuthSettings parsed = MAPPER.readValue("""
                {
                    "authentication_type": "OAUTH",
                    "client_id": "client-id",
                    "client_secret_hint": "beef"
                }
                """, ResourceAuthSettings.class);
        assertNull(parsed.getClientSecretHint(), "client_secret_hint must be read-only");
    }
}
