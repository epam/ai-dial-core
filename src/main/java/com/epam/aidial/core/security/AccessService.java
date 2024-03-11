package com.epam.aidial.core.security;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.Rule;
import com.epam.aidial.core.service.PublicationService;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import com.epam.aidial.core.util.UrlUtil;
import io.vertx.core.json.JsonObject;

import java.util.List;

public class AccessService {

    private final EncryptionService encryptionService;
    private final ShareService shareService;
    private final PublicationService publicationService;
    private final List<Rule> adminRules;

    public AccessService(EncryptionService encryptionService,
                         ShareService shareService,
                         PublicationService publicationService,
                         JsonObject settings) {
        this.encryptionService = encryptionService;
        this.shareService = shareService;
        this.publicationService = publicationService;
        this.adminRules = adminRules(settings);
    }

    public boolean hasWriteAccess(String filePath, String decryptedBucket, ProxyContext context) {
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

    public boolean hasWriteAccess(ResourceDescription resourceDescription, ProxyContext context) {
        String parentPath = resourceDescription.getParentPath();
        String filePath;
        if (parentPath == null) {
            filePath = resourceDescription.getName();
        } else {
            filePath = resourceDescription.getParentPath() + BlobStorageUtil.PATH_SEPARATOR + resourceDescription.getName();
        }
        return hasWriteAccess(filePath, resourceDescription.getBucketLocation(), context);
    }

    public boolean isSharedResource(ResourceDescription resource, ProxyContext context) {
        String actualUserLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String actualUserBucket = encryptionService.encrypt(actualUserLocation);
        return shareService.hasReadAccess(actualUserBucket, actualUserLocation, resource);
    }

    public boolean hasReviewAccess(ResourceDescription resource, ProxyContext context) {
        return publicationService.hasReviewAccess(context, resource);
    }

    public boolean hasPublicAccess(ResourceDescription resource, ProxyContext context) {
        return publicationService.hasPublicAccess(context, resource);
    }

    public boolean hasAdminAccess(ProxyContext context) {
        return RuleMatcher.match(context, adminRules);
    }

    public void filterForbidden(ProxyContext context, ResourceDescription descriptor, MetadataBase metadata) {
        if (descriptor.isPublic() && descriptor.isFolder() && !hasAdminAccess(context)) {
            ResourceFolderMetadata folder = (ResourceFolderMetadata) metadata;
            publicationService.filterForbidden(context, descriptor, folder);
        }
    }

    private static List<Rule> adminRules(JsonObject settings) {
        String rules = settings.getJsonObject("admin").getJsonArray("rules").toString();
        List<Rule> list = ProxyUtil.convertToObject(rules, Rule.LIST_TYPE);
        return (list == null) ? List.of() : list;
    }
}
