package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.configuration.EncryptionSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialsEncryptionServiceTest {

    private CredentialsEncryptionService service;
    private SecureRandom secureRandom;

    private byte[] key256;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        EncryptionSettings encryptionSettings = EncryptionSettings.builder()
                .algorithm("AES")
                .keySize(256)
                .cipherTransformation("AES/GCM/NoPadding")
                .ivLengthBytes(12)
                .gcmTagLengthBits(128)
                .build();

        secureRandom = SecureRandom.getInstanceStrong();
        service = new CredentialsEncryptionService(encryptionSettings, secureRandom);

        // Generate 256-bit AES key
        key256 = new byte[32];
        secureRandom.nextBytes(key256);
    }

    @Test
    void encryptAndDecrypt_shouldReturnOriginalPlaintext() {
        byte[] plain = "Hello, Encryption!".getBytes();
        byte[] aad = "AssociatedData".getBytes();

        byte[] encrypted = service.encrypt(plain, key256, aad);
        byte[] decrypted = service.decrypt(encrypted, key256, aad);

        assertArrayEquals(plain, decrypted);
    }

    @Test
    void encryptAndDecrypt_withoutAad_shouldWork() {
        byte[] plain = "No AAD here".getBytes();

        byte[] encrypted = service.encrypt(plain, key256, null);
        byte[] decrypted = service.decrypt(encrypted, key256, null);

        assertArrayEquals(plain, decrypted);
    }

    @Test
    void decrypt_withWrongAad_shouldThrowSecurityException() {
        byte[] plain = "Secret text!".getBytes();
        byte[] aadCorrect = "CorrectAAD".getBytes();
        byte[] aadWrong = "WrongAAD".getBytes();

        byte[] encrypted = service.encrypt(plain, key256, aadCorrect);

        SecurityException ex = assertThrows(SecurityException.class,
                () -> service.decrypt(encrypted, key256, aadWrong));
        assertTrue(ex.getMessage().contains("authentication tag mismatch"));
    }

    @Test
    void decrypt_withWrongKey_shouldThrowSecurityException() {
        byte[] plain = "Secret text!".getBytes();
        byte[] aad = "SomeAAD".getBytes();

        byte[] encrypted = service.encrypt(plain, key256, aad);

        byte[] wrongKey = new byte[32];
        secureRandom.nextBytes(wrongKey);

        SecurityException ex = assertThrows(SecurityException.class,
                () -> service.decrypt(encrypted, wrongKey, aad));
        assertTrue(ex.getMessage().contains("authentication tag mismatch"));
    }

    @Test
    void encrypt_withNullPlain_shouldThrowIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.encrypt(null, key256, null));
        assertEquals("plain must not be null", ex.getMessage());
    }

    @Test
    void decrypt_withNullCipher_shouldThrowIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.decrypt(null, key256, null));
        assertEquals("encrypted must not be null", ex.getMessage());
    }

    @Test
    void decrypt_withTooShortCipher_shouldThrowIllegalArgumentException() {
        byte[] tooShort = new byte[5];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.decrypt(tooShort, key256, null));
        assertEquals("Invalid encrypted payload", ex.getMessage());
    }

    @Test
    void encrypt_withNullKey_shouldThrowIllegalArgumentException() {
        byte[] plain = "some data".getBytes();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.encrypt(plain, null, null));
        assertEquals("contentEncryptionKey must not be null", ex.getMessage());
    }

    @Test
    void decrypt_withNullKey_shouldThrowIllegalArgumentException() {
        byte[] encrypted = new byte[16];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.decrypt(encrypted, null, null));
        assertEquals("contentEncryptionKey must not be null", ex.getMessage());
    }

}