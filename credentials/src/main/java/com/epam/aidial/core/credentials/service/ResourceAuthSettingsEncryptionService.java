package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

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
                    ? encryptValue(bucketInfo, aad, clientSecret)
                    : decryptValue(bucketInfo, aad, clientSecret);
            settings.setClientSecret(processedValue);
        }

    }

    private String encryptValue(BucketInfo bucketInfo, byte[] aad, String plainText) {
        byte[] plain = plainText.getBytes(UTF_8);
        byte[] encrypted = encryptionService.encrypt(bucketInfo, plain, aad);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private String decryptValue(BucketInfo bucketInfo, byte[] aad, String encryptedText) {
        byte[] encrypted = Base64.getDecoder().decode(encryptedText);
        byte[] plain = encryptionService.decrypt(bucketInfo, encrypted, aad);
        return new String(plain, UTF_8);
    }

}
