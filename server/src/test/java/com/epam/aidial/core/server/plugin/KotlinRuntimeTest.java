package com.epam.aidial.core.server.plugin;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KotlinRuntimeTest {

    @Test
    void testPluginCannotBorrowCoresKotlin() throws Exception {
        ClassLoader core = getClass().getClassLoader();
        core.loadClass("kotlin.jvm.internal.Reflection");
        try (PluginClassLoader plugin = new PluginClassLoader(new URL[0], core)) {
            assertThrows(ClassNotFoundException.class, () -> plugin.loadClass("kotlin.jvm.internal.Reflection"));
            assertThrows(ClassNotFoundException.class, () -> plugin.loadClass("kotlin.reflect.jvm.internal.ReflectionFactoryImpl"));
        }
    }

    @Test
    void testBundledStdlibDoesNotUseCoresOptionalReflection() throws Exception {
        ClassLoader core = getClass().getClassLoader();
        Class<?> coreReflection = core.loadClass("kotlin.jvm.internal.Reflection");
        URL stdlib = coreReflection.getProtectionDomain().getCodeSource().getLocation();
        try (PluginClassLoader plugin = new PluginClassLoader(new URL[]{stdlib}, core)) {
            Class<?> reflection = plugin.loadClass("kotlin.jvm.internal.Reflection");
            assertNotSame(coreReflection, reflection);
            assertSame(plugin, reflection.getClassLoader());
            Object kotlinClass = reflection.getMethod("getOrCreateKotlinClass", Class.class).invoke(null, String.class);
            assertEquals("kotlin.jvm.internal.ClassReference", kotlinClass.getClass().getName());
            assertSame(plugin, kotlinClass.getClass().getClassLoader());
            assertThrows(ClassNotFoundException.class, () -> plugin.loadClass("kotlin.reflect.jvm.internal.ReflectionFactoryImpl"));
        }
    }
}
