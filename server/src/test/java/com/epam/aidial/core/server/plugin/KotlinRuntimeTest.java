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

class KotlinRuntimeTest {

    @Test
    void testKotlinReflectIsOnTheClassPathNextToTheStdlib() {
        KClass<?> kotlinClass = Reflection.getOrCreateKotlinClass(String.class);

        assertEquals("kotlin.reflect.jvm.internal.KClassImpl", kotlinClass.getClass().getName());
        assertDoesNotThrow(() -> KClasses.getPrimaryConstructor(kotlinClass));
    }

    @Test
    void testStdlibAndReflectAreOfOneVersion() {
        String reflect = Reflection.getOrCreateKotlinClass(String.class).getClass().getPackage().getImplementationVersion();
        String stdlib = KotlinVersion.CURRENT.toString();

        assertNotNull(reflect, "kotlin-reflect has no Implementation-Version in its manifest");
        assertTrue(reflect.equals(stdlib) || reflect.startsWith(stdlib + "-"), "kotlin-reflect " + reflect + " with kotlin-stdlib " + stdlib);
    }
}
