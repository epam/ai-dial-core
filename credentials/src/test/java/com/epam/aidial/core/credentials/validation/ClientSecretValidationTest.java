package com.epam.aidial.core.credentials.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSecretValidationTest {

    @Test
    void testNullIsAllowed() {
        // null means "keep the stored secret" on update, not "set an empty one".
        assertDoesNotThrow(() -> ClientSecretValidation.validate(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "the-secret-value", "GOCSPX-abcdefghijklmnopqrstuvwxyz01"})
    void testAcceptsRealisticSecrets(String secret) {
        assertDoesNotThrow(() -> ClientSecretValidation.validate(secret));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void testRejectsBlank(String secret) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClientSecretValidation.validate(secret));
        assertTrue(e.getMessage().contains("CLIENT_SECRET"), e.getMessage());
    }

    @Test
    void testRejectsControlCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientSecretValidation.validate("secret\nvalue"));
    }

    @Test
    void testUnchangedSecretIsExempt() {
        // A re-write hands back what is stored — including values accepted before these rules existed, as
        // PublicationService does when it rewrites an application's links.
        assertDoesNotThrow(() -> ClientSecretValidation.validate("short", "short"));
        assertDoesNotThrow(() -> ClientSecretValidation.validate("  ", "  "));
    }

    @Test
    void testChangedSecretIsStillChecked() {
        assertThrows(IllegalArgumentException.class, () -> ClientSecretValidation.validate("short", "other-secret"));
        assertThrows(IllegalArgumentException.class, () -> ClientSecretValidation.validate("short", null));
        assertDoesNotThrow(() -> ClientSecretValidation.validate("a-new-long-secret", "short"));
        assertDoesNotThrow(() -> ClientSecretValidation.validate(null, "short"));
    }

    @Test
    void testRejectsTooShort() {
        assertThrows(IllegalArgumentException.class, () -> ClientSecretValidation.validate("short"));
        assertDoesNotThrow(() -> ClientSecretValidation.validate("x".repeat(ClientSecretValidation.MIN_LENGTH)));
    }
}
