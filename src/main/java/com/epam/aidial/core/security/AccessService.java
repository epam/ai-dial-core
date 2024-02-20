package com.epam.aidial.core.security;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.UrlUtil;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class AccessService {

    private final EncryptionService encryptionService;

    public boolean hasWriteAccess(String bucket, String filePath, ProxyContext context) {
        String urlDecodedBucket = UrlUtil.decodePath(bucket);
        String decryptedBucket = encryptionService.decrypt(urlDecodedBucket);
        String expectedUserBucket = BlobStorageUtil.buildUserBucket(context);
        if (expectedUserBucket.equals(decryptedBucket)) {
            return true;
        }
        String expectedAppDataBucket = BlobStorageUtil.buildAppDataBucket(context);
        if (expectedAppDataBucket != null && expectedAppDataBucket.equals(decryptedBucket)) {
            return filePath.startsWith(BlobStorageUtil.APPDATA_PATTERN.formatted(UrlUtil.encodePath(context.getSourceDeployment())));
        }
        return false;
    }

    public boolean hasWriteAccess(String url, ProxyContext context) {
        if (url == null) {
            return false;
        }
        int index = url.indexOf(BlobStorageUtil.PATH_SEPARATOR);
        if (index < 0) {
            return false;
        }
        String bucket = url.substring(0, index);
        String path = url.substring(index + 1);
        return hasWriteAccess(bucket, path, context);
    }
}
