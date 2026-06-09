package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.annotation.EncryptedField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Locks the slice 2S.10 invariant on which entity fields carry {@link EncryptedField}: the
 * full-blob secrets ({@code Key.key}, {@code Upstream.key}, {@code Upstream.secretExtraData}) and
 * nothing else. In particular, {@code ResourceAuthSettings.clientSecret} and
 * {@code ResourceAuthSettings.codeVerifier} must NOT carry the marker — they go through
 * {@code ResourceAuthSettingsEncryptionService} (3S.0-pre extension), not {@link
 * com.epam.aidial.core.server.config.SecretFieldProcessor}.
 */
class EncryptedFieldNegativeAnnotationTest {

    /** Curated list of config-package classes that we audit; controlled set, not a classpath scan. */
    private static final List<Class<?>> CONFIG_CLASSES = List.of(
            Key.class,
            Upstream.class,
            Model.class,
            Application.class,
            Interceptor.class,
            Role.class,
            Route.class,
            ToolSet.class,
            ResourceAuthSettings.class);

    @Test
    void resourceAuthSettingsClientSecretAndCodeVerifierAreNotMarked() {
        for (String fieldName : List.of("clientSecret", "codeVerifier")) {
            Field f = findField(ResourceAuthSettings.class, fieldName);
            assertFalse(f.isAnnotationPresent(EncryptedField.class),
                    () -> "ResourceAuthSettings." + fieldName
                            + " must NOT carry @EncryptedField (handled by ResourceAuthSettingsEncryptionService).");
        }
    }

    @Test
    void encryptedFieldOnlyOnExpectedCarriers() {
        Set<String> expected = Set.of(
                "com.epam.aidial.core.config.Key#key",
                "com.epam.aidial.core.config.Upstream#key",
                "com.epam.aidial.core.config.Upstream#secretExtraData");

        Set<String> actual = new java.util.HashSet<>();
        for (Class<?> cls : CONFIG_CLASSES) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.isAnnotationPresent(EncryptedField.class)) {
                    actual.add(cls.getName() + "#" + f.getName());
                }
            }
        }

        assertEquals(expected, actual,
                "@EncryptedField is only expected on Key.key, Upstream.key, Upstream.secretExtraData; "
                        + "any divergence requires an architect plan update.");
    }

    private static Field findField(Class<?> cls, String name) {
        return Arrays.stream(cls.getDeclaredFields())
                .filter(f -> f.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Field not found: " + cls.getName() + "." + name));
    }
}
