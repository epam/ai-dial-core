package com.epam.aidial.core.server.security;

import lombok.experimental.UtilityClass;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

@UtilityClass
public class ApiKeyGenerator {

    private static final String DICT = "0123456789abcdefghijklmnopqrstuvwxyz-_ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int KEY_LENGTH = 32;

    private static final SecureRandom SECURE_RANDOM;

    static {
        try {
            SECURE_RANDOM = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String generateKey() {
        return SECURE_RANDOM
                .ints(KEY_LENGTH, 0, DICT.length())
                .mapToObj(DICT::charAt)
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                .toString();
    }

    /**
     * Tells whether the key has the shape {@link #generateKey()} produces. A key of another origin may have it too.
     */
    static boolean isGeneratedKey(String key) {
        return key.length() == KEY_LENGTH && key.chars().allMatch(symbol -> DICT.indexOf(symbol) >= 0);
    }
}
