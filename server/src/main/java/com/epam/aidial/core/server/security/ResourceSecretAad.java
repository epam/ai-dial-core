package com.epam.aidial.core.server.security;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;

import java.nio.charset.StandardCharsets;

/**
 * Derives the additional authenticated data that binds an encrypted field to the resource holding it.
 *
 * <p>The AAD is the resource's physical path, so ciphertext is only readable at the path it was written
 * to: re-addressing a resource without re-encrypting it makes its secrets unrecoverable. Anything that
 * moves encrypted resources has to decrypt with the source path and encrypt with the destination one,
 * which is why the path overload exists alongside the descriptor one.
 */
public final class ResourceSecretAad {

    private ResourceSecretAad() {
    }

    public static byte[] deriveFor(ResourceDescriptor descriptor) {
        return deriveFor(descriptor.getAbsoluteFilePath());
    }

    public static byte[] deriveFor(String absoluteFilePath) {
        return absoluteFilePath.getBytes(StandardCharsets.UTF_8);
    }
}
