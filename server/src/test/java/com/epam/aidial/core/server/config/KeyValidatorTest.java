package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KeyValidatorTest {

    private static Key key(String secret, String project, String role) {
        Key key = new Key();
        key.setKey(secret);
        key.setProject(project);
        key.setRole(role);
        return key;
    }

    // --- validateRequiredFields -------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("requiredFieldsCases")
    void testValidateRequiredFields(String secret, String project, String role, String expectedError) {
        assertEquals(expectedError, KeyValidator.validateRequiredFields(key(secret, project, role)));
    }

    private static List<Arguments> requiredFieldsCases() {
        return List.of(
                Arguments.of("secret", "proj", "role1", null),
                Arguments.of(" ", "proj", "role1", "Key.key must be provided explicitly"),
                Arguments.of(null, "proj", "role1", "Key.key must be provided explicitly"),
                Arguments.of("secret", " ", "role1", "Project key is undefined"),
                Arguments.of("secret", null, "role1", "Project key is undefined"),
                Arguments.of("secret", "proj", null,
                        "Invalid key: at least one role must be assigned to the key proj"),
                // Secret is checked before project/role even when all three are missing.
                Arguments.of(null, null, null, "Key.key must be provided explicitly"));
    }

    @Test
    void testValidateRequiredFieldsAcceptsRolesListWithoutSingleRole() {
        Key withRoles = key("secret", "proj", null);
        withRoles.setRoles(List.of("role1", "role2"));
        assertNull(KeyValidator.validateRequiredFields(withRoles));
    }

    // --- validateProjectAndRoles -------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("projectAndRolesCases")
    void testValidateProjectAndRoles(String secret, String project, String role, String expectedError) {
        assertEquals(expectedError, KeyValidator.validateProjectAndRoles(key(secret, project, role)));
    }

    private static List<Arguments> projectAndRolesCases() {
        return List.of(
                // Unlike validateRequiredFields, a blank secret alone is not an error here —
                // ApiKeyStore's rebuild loader back-fills it after this check runs.
                Arguments.of(null, "proj", "role1", null),
                Arguments.of(null, " ", "role1", "Project key is undefined"),
                Arguments.of(null, "proj", "", "Invalid key: at least one role must be assigned to the key proj"));
    }

    // --- validateSecretNotTaken ----------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("secretNotTakenCases")
    void testValidateSecretNotTaken(String candidateSecret, String oldSecret, String expectedError) {
        Config config = new Config();
        config.getKeys().put("keys/platform/other", key("shared-secret", "proj-a", "role1"));

        assertEquals(expectedError, KeyValidator.validateSecretNotTaken(
                config, "keys/platform/self", key(candidateSecret, "proj-b", "role1"), oldSecret));
    }

    private static List<Arguments> secretNotTakenCases() {
        String taken = "Key secret is already used by a different key entity";
        return List.of(
                Arguments.of("fresh-secret", "own-secret", null),
                Arguments.of("shared-secret", "own-secret", taken),
                // Own secret didn't change on this write — a legacy duplicate pair isn't re-flagged.
                Arguments.of("shared-secret", "shared-secret", null),
                // No prior secret (create) and a brand-new, free secret.
                Arguments.of("fresh-secret", null, null),
                // No prior secret and a brand-new secret that's already taken.
                Arguments.of("shared-secret", null, taken));
    }

    // --- isKeySecretChangedAndTakenByAnotherKey (raw collision scan, not exercised above) -----

    @Test
    void testIsKeySecretChangedAndTakenByAnotherKeyDetectsFileSourcedEntryCollision() {
        // File-sourced entries sit under their raw secret as the map key, with the secret
        // back-filled into the value by ApiKeyStore before the merged config is served.
        Config config = new Config();
        config.getKeys().put("file-secret", key("file-secret", "proj", null));

        assertTrue(KeyValidator.isKeySecretChangedAndTakenByAnotherKey(
                config, "keys/platform/self", key("file-secret", "proj", null), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"own-secret", " ", "free-secret"})
    void testIsKeySecretChangedAndTakenByAnotherKeySkipsSelfBlankAndNull(String candidateSecret) {
        Config config = new Config();
        config.getKeys().put("keys/platform/self", key("own-secret", "proj", null));
        config.getKeys().put("keys/platform/blank", key(null, "proj", null));
        config.getKeys().put("keys/platform/null-value", null);

        // oldSecret=null (not read from the map above) forces every row past the unchanged-secret
        // short-circuit and into the actual collision scan this test targets — self-map-key
        // exclusion, blank candidate, blank/null map entries.
        assertFalse(KeyValidator.isKeySecretChangedAndTakenByAnotherKey(
                config, "keys/platform/self", key(candidateSecret, "proj", null), null));
    }
}
