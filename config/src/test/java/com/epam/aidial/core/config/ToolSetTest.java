package com.epam.aidial.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertNull;

class ToolSetTest {

    private static ToolSet toolSet(AuthenticationType type) {
        ToolSet toolSet = new ToolSet();
        toolSet.setAuthSettings(ResourceAuthSettings.builder()
                .authenticationType(type)
                .clientSecret("my-client-secret")
                .codeVerifier("code-verifier")
                .build());
        return toolSet;
    }

    // A blob of any type can carry a clientSecret — written before the per-type validators existed, or
    // hand-seeded — and none of them may be echoed back, encrypted or (once the hint path decrypts it) plain.
    @ParameterizedTest
    @EnumSource(AuthenticationType.class)
    void testClearAuthSettingsStripsSecretsForEveryAuthenticationType(AuthenticationType type) {
        ToolSet toolSet = toolSet(type);

        toolSet.clearAuthSettings(false);

        assertNull(toolSet.getAuthSettings().getClientSecret());
        assertNull(toolSet.getAuthSettings().getCodeVerifier());
        assertNull(toolSet.getAuthSettings().getClientSecretHint());
    }

    @ParameterizedTest
    @EnumSource(AuthenticationType.class)
    void testClearAuthSettingsStripsSecretsWhenRevealingTheHint(AuthenticationType type) {
        ToolSet toolSet = toolSet(type);

        toolSet.clearAuthSettings(true);

        assertNull(toolSet.getAuthSettings().getClientSecret());
        assertNull(toolSet.getAuthSettings().getCodeVerifier());
    }
}
