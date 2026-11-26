package com.epam.aidial.core.server.plugin;

import kotlin.KotlinVersion;
import kotlin.jvm.internal.Reflection;
import kotlin.reflect.KClass;
import kotlin.reflect.full.KClasses;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin runs on Core's Kotlin: its class loader asks Core's first, and Kotlin finds kotlin-reflect through the class loader
 * of kotlin-stdlib, which is Core's. So Core must carry both, of one version; see docs/dynamic-settings/plugins.md.
 */
class KotlinRuntimeTest {

    @Test
    void testKotlinReflectIsOnTheClassPathNextToTheStdlib() {
        KClass<?> kotlinClass = Reflection.getOrCreateKotlinClass(String.class);

        // With the stdlib alone, Kotlin falls back to its basic ClassReference, and kotlin-reflect's functions cannot cast it
        assertEquals("kotlin.reflect.jvm.internal.KClassImpl", kotlinClass.getClass().getName());
        assertDoesNotThrow(() -> KClasses.getPrimaryConstructor(kotlinClass));
    }

    @Test
    void testStdlibAndReflectAreOfOneVersion() {
        String reflect = Reflection.getOrCreateKotlinClass(String.class).getClass().getPackage().getImplementationVersion();
        String stdlib = KotlinVersion.CURRENT.toString();

        assertNotNull(reflect, "kotlin-reflect has no Implementation-Version in its manifest");
        // the manifest of kotlin-reflect says "1.9.10-release-459" for the version 1.9.10
        assertTrue(reflect.equals(stdlib) || reflect.startsWith(stdlib + "-"), "kotlin-reflect " + reflect + " with kotlin-stdlib " + stdlib);
    }
}
