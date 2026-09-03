package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.credentials.data.configuration.EncryptionSettings;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.ContentEncryptionKeyService;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.credentials.encryption.DataEncryptionService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.LegacyStorageLayout;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.resource.StorageLayouts;
import com.epam.aidial.core.storage.resource.TenantRootedStorageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The AAD of an encrypted field lives on inside the stored ciphertext, so it must not change when the
 * storage layout does. These tests use real AES-GCM — a mocked cipher ignores the AAD and would pass
 * regardless of which path it was derived from.
 */
public class SecretFieldProcessorLayoutTest {

    private static final BucketInfo BUCKET = new BucketInfo("platform", "platform/");

    private CredentialEncryptionService encryptionService;
    private SecretFieldProcessor processor;
    private ResourceDescriptor descriptor;

    @BeforeEach
    public void setUp() {
        EncryptionSettings settings = EncryptionSettings.builder()
                .algorithm("AES")
                .keySize(256)
                .cipherTransformation("AES/GCM/NoPadding")
                .ivLengthBytes(12)
                .gcmTagLengthBits(128)
                .build();
        SecureRandom random = new SecureRandom();
        byte[] contentEncryptionKey = new byte[32];
        random.nextBytes(contentEncryptionKey);
        ContentEncryptionKeyService keyService = mock(ContentEncryptionKeyService.class);
        when(keyService.getOrCreateKey(any(BucketInfo.class))).thenReturn(contentEncryptionKey);

        encryptionService = new CredentialEncryptionService(keyService, new DataEncryptionService(settings, random));
        processor = new SecretFieldProcessor(encryptionService, BUCKET);
        descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.PROJECT_KEY, "platform", "platform/", "test-key");
    }

    @AfterEach
    public void restoreLegacyLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testSecretEncryptedUnderLegacyLayoutDecryptsUnderTenantRootedLayout() {
        Key key = new Key();
        key.setKey("plain-secret");
        processor.encryptFields(key, descriptor);
        assertTrue(key.getKey().startsWith(SecretFieldProcessor.ENC_PREFIX));

        StorageLayouts.useLayout(new TenantRootedStorageLayout("acme"));
        processor.decryptFields(key, descriptor);

        assertEquals("plain-secret", key.getKey());
    }

    // Under the legacy layout the physical and stable paths coincide, so the divergence only exists on the
    // tenant-rooted side: ciphertext bound to the tenant-shaped physical path must not decrypt against the
    // stable AAD. This is also the guard proving the AAD participates at all — with a cipher that ignored
    // it, the round-trip test above would pass for any path.
    @Test
    public void testPhysicalPathAadDoesNotMatchTheStableAad() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout("acme"));

        byte[] physicalPathAad = descriptor.getAbsoluteFilePath().getBytes(StandardCharsets.UTF_8);
        byte[] cipher = encryptionService.encrypt(BUCKET, "plain-secret".getBytes(StandardCharsets.UTF_8), physicalPathAad);
        Key key = new Key();
        key.setKey(SecretFieldProcessor.ENC_PREFIX + Base64.getEncoder().encodeToString(cipher) + SecretFieldProcessor.ENC_SUFFIX);

        assertThrows(SecurityException.class, () -> processor.decryptFields(key, descriptor));
    }
}
