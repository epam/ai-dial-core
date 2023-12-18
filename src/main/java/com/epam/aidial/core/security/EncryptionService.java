package com.epam.aidial.core.security;

import com.epam.aidial.core.config.Encryption;
import com.epam.aidial.core.util.Base58;
import lombok.extern.slf4j.Slf4j;

import java.security.spec.KeySpec;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

@Slf4j
public class EncryptionService {

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    private final SecretKey key;
    private final IvParameterSpec iv = new IvParameterSpec(
            new byte[]{25, -13, -25, -119, -42, 117, -118, -128, -101, 20, -103, -81, -48, -23, -54, -113});

    public EncryptionService(Encryption config) {
        this(config.getPassword(), config.getSalt());
    }

    EncryptionService(String password, String salt) {
        Objects.requireNonNull(password, "Encryption password is not set");
        Objects.requireNonNull(salt, "Encryption salt is not set");
        try {
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(), 3000, 256);
            key = new SecretKeySpec(secretKeyFactory.generateSecret(spec).getEncoded(), "AES");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String encrypt(String value) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, iv);
            return Base58.encode(cipher.doFinal(value.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public String decrypt(String value) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, iv);
            return new String(cipher.doFinal(Base58.decode(value)));
        } catch (Exception e) {
            log.error("Failed to decrypt value " + value, e);
            return null;
        }
    }
}
