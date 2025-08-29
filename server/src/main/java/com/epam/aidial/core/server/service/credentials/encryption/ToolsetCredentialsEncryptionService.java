package com.epam.aidial.core.server.service.credentials.encryption;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

@Slf4j
@RequiredArgsConstructor
public class ToolsetCredentialsEncryptionService {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12; // 96-bit nonce as recommended for GCM
    private static final int GCM_TAG_LENGTH_BITS = 128; // 16-byte authentication tag
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public byte[] encrypt(byte[] plain, byte[] contentEncryptionKey, byte[] aad) {
        if (plain == null) {
            throw new IllegalArgumentException("plain must not be null");
        }
        validateKey(contentEncryptionKey);

        byte[] iv = new byte[IV_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(contentEncryptionKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }

            byte[] cipherText = cipher.doFinal(plain);

            byte[] output = new byte[IV_LENGTH_BYTES + cipherText.length];
            System.arraycopy(iv, 0, output, 0, IV_LENGTH_BYTES);
            System.arraycopy(cipherText, 0, output, IV_LENGTH_BYTES, cipherText.length);
            return output;
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] encrypted, byte[] contentEncryptionKey, byte[] aad) {
        if (encrypted == null) {
            throw new IllegalArgumentException("encrypted must not be null");
        }
        validateKey(contentEncryptionKey);
        if (encrypted.length < IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
            throw new IllegalArgumentException("Invalid encrypted payload");
        }

        byte[] iv = new byte[IV_LENGTH_BYTES];
        System.arraycopy(encrypted, 0, iv, 0, IV_LENGTH_BYTES);

        byte[] cipherText = new byte[encrypted.length - IV_LENGTH_BYTES];
        System.arraycopy(encrypted, IV_LENGTH_BYTES, cipherText, 0, cipherText.length);

        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(contentEncryptionKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }

            return cipher.doFinal(cipherText);
        } catch (AEADBadTagException e) {
            throw new SecurityException("Decryption failed: authentication tag mismatch (AAD, key, or ciphertext wrong)", e);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    private static void validateKey(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("contentEncryptionKey must not be null");
        }
        int len = key.length;
        if (len != 32) {
            throw new IllegalArgumentException("contentEncryptionKey must be 32 bytes for AES-256");
        }
    }

}
