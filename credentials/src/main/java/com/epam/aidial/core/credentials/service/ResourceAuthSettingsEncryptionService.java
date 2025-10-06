package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@RequiredArgsConstructor
public class ResourceAuthSettingsEncryptionService {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    private final CredentialEncryptionService encryptionService;

    public void encrypt(String resourceId,
                        BucketInfo bucketInfo,
                        ResourceAuthSettings resourceAuthSettings) {
        processFields(resourceId, bucketInfo, resourceAuthSettings, true);
    }

    public void decrypt(String resourceId,
                        BucketInfo bucketInfo,
                        ResourceAuthSettings resourceAuthSettings) {
        processFields(resourceId, bucketInfo, resourceAuthSettings, false);
    }

    private void processFields(String resourceId,
                               BucketInfo bucketInfo,
                               ResourceAuthSettings settings,
                               boolean encrypt) {

        byte[] aad = resourceId.getBytes(UTF_8);

        String clientSecret = settings.getClientSecret();
        if (clientSecret != null) {
            String processedValue = encrypt
                    ? encryptValue(bucketInfo, clientSecret, aad)
                    : decryptValue(bucketInfo, clientSecret, aad);
            settings.setClientSecret(processedValue);
        }

    }

    private String encryptValue(@Nonnull BucketInfo bucketInfo, @Nonnull String plainText, @Nullable byte[] aad) {
        try {
            byte[] plain = plainText.getBytes(UTF_8);
            byte[] encrypted = encryptionService.encrypt(bucketInfo, plain, aad);
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (RuntimeException e) {
            throw new EncryptionException("Failed to encrypt auth settings", e);
        }
    }

    private String decryptValue(@Nonnull BucketInfo bucketInfo, @Nonnull String encryptedText, @Nullable byte[] aad) {
        try {
            byte[] encrypted = Base64.getDecoder().decode(encryptedText);
            byte[] plain = encryptionService.decrypt(bucketInfo, encrypted, aad);
            return new String(plain, UTF_8);
        } catch (RuntimeException e) {
            throw new EncryptionException("Failed to decrypt auth setting", e);
        }
    }

}
