package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.configuration.EncryptionSettings;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import lombok.extern.slf4j.Slf4j;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Service class for encrypting and decrypting credential data using AES-GCM.
 *
 * <p>Uses a provided Content Encryption Key (CEK) to perform symmetric encryption.
 * The CEK itself is typically decrypted via a {@link ContentEncryptionKeyManager}.
 *
 * <p>The output of encryption prepends the randomly generated IV to the ciphertext
 * for use during decryption.
 */
@Slf4j
public class DataEncryptionService {

    private final String cipherTransformation;
    private final int ivLengthBytes;
    private final int gcmTagLengthBits;
    private final SecureRandom secureRandom;

    public DataEncryptionService(EncryptionSettings encryptionSettings, SecureRandom secureRandom) {
        this.cipherTransformation = encryptionSettings.getCipherTransformation();
        this.ivLengthBytes = encryptionSettings.getIvLengthBytes();
        this.gcmTagLengthBits = encryptionSettings.getGcmTagLengthBits();

        this.secureRandom = secureRandom;
    }

    /**
     * Encrypts the given plaintext using AES-GCM with the provided CEK and
     * optional Additional Authenticated Data (AAD).
     *
     * @param plain                plaintext bytes
     * @param contentEncryptionKey CEK for encryption
     * @param aad                  optional AAD for authentication
     * @return encrypted payload with IV prepended
     */
    public byte[] encrypt(byte[] plain, byte[] contentEncryptionKey, byte[] aad) {
        if (plain == null) {
            throw new IllegalArgumentException("plain must not be null");
        }
        validateKey(contentEncryptionKey);

        byte[] iv = new byte[ivLengthBytes];
        secureRandom.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(cipherTransformation);
            SecretKeySpec keySpec = new SecretKeySpec(contentEncryptionKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(gcmTagLengthBits, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }

            byte[] cipherText = cipher.doFinal(plain);

            byte[] output = new byte[ivLengthBytes + cipherText.length];
            System.arraycopy(iv, 0, output, 0, ivLengthBytes);
            System.arraycopy(cipherText, 0, output, ivLengthBytes, cipherText.length);
            return output;
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Encryption failed", e);
        }
    }

    /**
     * Decrypts the given AES-GCM encrypted payload with the provided CEK
     * and optional Additional Authenticated Data (AAD).
     *
     * @param encrypted            encrypted bytes with IV prepended
     * @param contentEncryptionKey CEK for decryption
     * @param aad                  optional AAD for authentication
     * @return decrypted plaintext bytes
     */
    public byte[] decrypt(byte[] encrypted, byte[] contentEncryptionKey, byte[] aad) {
        if (encrypted == null) {
            throw new IllegalArgumentException("encrypted must not be null");
        }
        validateKey(contentEncryptionKey);
        if (encrypted.length < ivLengthBytes + (gcmTagLengthBits / 8)) {
            throw new IllegalArgumentException("Invalid encrypted payload");
        }

        byte[] iv = new byte[ivLengthBytes];
        System.arraycopy(encrypted, 0, iv, 0, ivLengthBytes);

        byte[] cipherText = new byte[encrypted.length - ivLengthBytes];
        System.arraycopy(encrypted, ivLengthBytes, cipherText, 0, cipherText.length);

        try {
            Cipher cipher = Cipher.getInstance(cipherTransformation);
            SecretKeySpec keySpec = new SecretKeySpec(contentEncryptionKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(gcmTagLengthBits, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }

            return cipher.doFinal(cipherText);
        } catch (AEADBadTagException e) {
            throw new EncryptionException("Decryption failed: authentication tag mismatch (AAD, key, or ciphertext wrong)", e);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Decryption failed", e);
        }
    }

    /**
     * Minimum length, in bytes, of a payload produced by {@link #encrypt}: the prepended IV plus the GCM
     * authentication tag. Any shorter byte sequence cannot be a ciphertext from this service.
     *
     * @return {@code ivLengthBytes + gcmTagLengthBits / 8}
     */
    public int minEncryptedLength() {
        return ivLengthBytes + (gcmTagLengthBits / 8);
    }

    private static void validateKey(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("contentEncryptionKey must not be null");
        }
    }

}
